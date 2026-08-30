package com.maintenance.app.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The seam driven end to end: a plan puts itself on the week, the week gives the tick back, the
 * clock moves, and the next occurrence goes on.
 *
 * Both sides are fakes, which is the point — the round is pure orchestration over two interfaces,
 * so the awkward cases (a deleted task, a carried-forward one, a paused plan) can be produced on
 * demand rather than waited for.
 */
class UpkeepRoundTest {

    private val day = 86_400_000L
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    // --- the fake week planner -------------------------------------------------------------

    private class FakeWeek : UpkeepWeek {
        var nextId = 1
        val tasks = LinkedHashMap<String, UpkeepTasks.PublishedTask>()
        /** Titles the planner refuses, standing in for "you already wrote that one by hand". */
        val declines = mutableSetOf<String>()
        /** old task id → the row a week close carried it into. */
        private val carriedInto = mutableMapOf<String, String>()
        var published = 0

        override suspend fun publish(title: String, due: LocalDate, note: String): String? {
            if (title in declines) return null
            val id = "task-${nextId++}"
            tasks[id] = UpkeepTasks.PublishedTask(id, title, due, completed = false, completedAtMillis = null)
            published++
            return id
        }

        override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean {
            val task = tasks[taskId] ?: return false
            tasks[taskId] = task.copy(dueDate = due, title = title)
            return true
        }

        override suspend fun retire(taskId: String): Boolean = tasks.remove(taskId) != null

        /**
         * What the bridge does: follow carry-forward hops to the row that is actually live. The
         * original stays where it was — LifeOps keeps it, marked carried, in the week it belonged to.
         */
        override suspend fun state(taskId: String): UpkeepTasks.PublishedTask? {
            var id = taskId
            var hops = 0
            while (hops++ < 50) id = carriedInto[id] ?: break
            return tasks[id]
        }

        /** Somebody ticks it in LifeOps. */
        fun complete(taskId: String, at: Long) {
            tasks[taskId] = tasks.getValue(taskId).copy(completed = true, completedAtMillis = at)
        }

        /** Somebody deletes it in LifeOps. */
        fun delete(taskId: String) {
            tasks.remove(taskId)
        }

        /** A week closes without it being done, and it carries into a new row with a new id. */
        fun carryForward(taskId: String): String {
            val old = tasks.getValue(taskId)
            val id = "task-${nextId++}"
            tasks[id] = old.copy(id = id)
            carriedInto[taskId] = id
            return id
        }

        /** A week closes without it being done and without it being carried: stranded. */
        fun strand(taskId: String) {
            tasks[taskId] = tasks.getValue(taskId).copy(open = false)
        }
    }

    // --- the fake store --------------------------------------------------------------------

    private class FakeStore(var plan: UpkeepPlan) : UpkeepStore {
        var link = UpkeepTasks.TaskLink.NONE
        val logged = mutableListOf<Pair<String, Long>>()
        var meter: MeterState? = null
        var assetName = "Truck"

        override suspend fun planSnapshots(now: Long): List<PlanSnapshot> = listOf(
            PlanSnapshot(plan, assetName, meter, link, Upkeep.evaluate(plan, now, meter))
        )

        override suspend fun setPlanLink(planId: String, taskId: String?, publishedDue: LocalDate?) {
            link = UpkeepTasks.TaskLink(taskId, publishedDue)
        }

        override suspend fun completeFromWeek(planId: String, completedAt: Long): Boolean {
            logged += planId to completedAt
            // What the repository does: the service is logged, the clock moves, the link is dropped.
            plan = plan.copy(lastDoneAt = completedAt)
            link = UpkeepTasks.TaskLink.NONE
            return true
        }
    }

    private fun plan(everyDays: Int? = 90, active: Boolean = true, publish: Boolean = true) = UpkeepPlan(
        id = "plan-1",
        assetId = "asset-1",
        title = "Oil change",
        everyDays = everyDays,
        createdAt = now,
        active = active,
        publishToLifeOps = publish
    )

    private fun round(week: FakeWeek, store: FakeStore, onPublished: () -> Unit = {}) =
        UpkeepRound(week, store, zone, onPublished)

    // --- the tests -------------------------------------------------------------------------

    @Test
    fun `a plan puts itself on the week, once`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        var flagged = false

        val first = round(week, store) { flagged = true }.run(now)
        assertEquals(1, first.published)
        assertTrue(flagged)
        assertEquals("Truck: Oil change", week.tasks.values.single().title)
        assertEquals(LocalDate.of(2026, 5, 30), week.tasks.values.single().dueDate)

        // Running it again changes nothing — the round is a reconciliation, not a command.
        val second = round(week, store).run(now)
        assertEquals(UpkeepRound.Report(), second)
        assertEquals(1, week.tasks.size)
    }

    @Test
    fun `ticking it in LifeOps logs the service and puts the next one on the week`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)
        val firstTask = week.tasks.keys.single()

        val completedAt = now + 88 * day
        week.complete(firstTask, completedAt)

        val report = round(week, store).run(completedAt)

        assertEquals(1, report.completed)
        assertEquals(listOf("plan-1" to completedAt), store.logged)
        // The clock moved, so the *next* occurrence is on the week — 90 days after it was done.
        assertEquals(1, report.published)
        val next = week.tasks.values.single { !it.completed }
        assertEquals(LocalDate.of(2026, 8, 26), next.dueDate)
        assertEquals(next.id, store.link.taskId)
    }

    @Test
    fun `a task deleted in LifeOps is not put back, and does not block the next occurrence`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)
        val published = store.link.taskId!!

        week.delete(published)
        val forgetting = round(week, store).run(now)

        assertEquals(1, forgetting.forgotten)
        assertNull(store.link.taskId)
        assertTrue("nothing should be re-published", week.tasks.isEmpty())

        // Still nothing on the next round: the deletion was a decision about this occurrence.
        assertEquals(UpkeepRound.Report(), round(week, store).run(now))

        // …but once the job is done and the clock moves on, the next occurrence publishes normally.
        store.plan = store.plan.copy(lastDoneAt = now + 10 * day)
        val later = round(week, store).run(now + 10 * day)
        assertEquals(1, later.published)
        assertEquals(LocalDate.of(2026, 6, 9), store.link.publishedDue)
    }

    @Test
    fun `a task carried into a new week is followed, not duplicated`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)
        val original = store.link.taskId!!

        val carried = week.carryForward(original)
        val report = round(week, store).run(now)

        // The link now names the row that is live, and nothing was published to replace it.
        assertEquals(carried, store.link.taskId)
        assertEquals(0, report.published)
        // Two rows exist because LifeOps made the second one, not because the round published again.
        assertEquals(2, week.tasks.size)
        assertEquals(1, week.published)
    }

    @Test
    fun `a task stranded in a closed week is replaced on the current one`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)
        val stranded = store.link.taskId!!
        week.strand(stranded)

        // Long enough later that the plan is overdue and the job still wants doing.
        val later = now + 120 * day
        val report = round(week, store).run(later)

        assertEquals(1, report.published)
        assertEquals(2, week.tasks.size)
        // The stranded one is left exactly where it is — that week has already been reviewed.
        assertTrue(week.tasks.containsKey(stranded))
    }

    @Test
    fun `pausing a plan takes its task off the week, and resuming puts one back`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)

        store.plan = store.plan.copy(active = false)
        val paused = round(week, store).run(now)
        assertEquals(1, paused.retired)
        assertTrue(week.tasks.isEmpty())
        assertNull(store.link.publishedDue)

        store.plan = store.plan.copy(active = true)
        assertEquals(1, round(week, store).run(now).published)
    }

    @Test
    fun `a plan kept off the week never reaches it`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan(publish = false))

        assertEquals(UpkeepRound.Report(), round(week, store).run(now))
        assertTrue(week.tasks.isEmpty())
    }

    @Test
    fun `a title LifeOps already has is not fought over`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        week.declines += "Truck: Oil change"

        val first = round(week, store).run(now)
        assertEquals(0, first.published)
        // The occurrence is remembered even though nothing was created, so the round stops trying.
        assertEquals(LocalDate.of(2026, 5, 30), store.link.publishedDue)
        assertNull(store.link.taskId)
        assertEquals(UpkeepRound.Report(), round(week, store).run(now))
        assertEquals(0, week.published)
    }

    @Test
    fun `a date that moves reschedules the task in place`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(plan())
        round(week, store).run(now)
        val taskId = store.link.taskId!!

        // A service logged early moves the whole schedule forward.
        store.plan = store.plan.copy(lastDoneAt = now - 30 * day)
        val report = round(week, store).run(now)

        assertEquals(1, report.rescheduled)
        assertEquals(1, week.tasks.size)
        assertEquals(LocalDate.of(2026, 4, 30), week.tasks.getValue(taskId).dueDate)
        assertEquals(LocalDate.of(2026, 4, 30), store.link.publishedDue)
    }

    @Test
    fun `no week planner in the process is a no-op, not a failure`() = runTest {
        // The publisher's null-bridge case, expressed at this level: nothing to reconcile against.
        val store = FakeStore(plan())
        val report = UpkeepRound(
            week = object : UpkeepWeek {
                override suspend fun publish(title: String, due: LocalDate, note: String): String? = null
                override suspend fun reschedule(taskId: String, due: LocalDate, title: String) = false
                override suspend fun retire(taskId: String) = false
                override suspend fun state(taskId: String): UpkeepTasks.PublishedTask? = null
            },
            store = store,
            zone = zone
        ).run(now)

        assertEquals(0, report.published)
        assertNull(store.link.taskId)
    }
}
