package com.finance.app.logic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The round driven against a fake week and a fake store, which is the only way to check the things
 * that actually go wrong: a task carried into a new week under a new id, two bills racing for one
 * task, a planner that isn't there at all.
 */
class BillRoundTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val today = LocalDate.parse("2026-09-09")
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * A week that behaves like LifeOps in the two ways that matter to a round.
     *
     * **It adopts by title.** LifeOps' bridge looks for an open task of the same name before adding
     * one, so that a task you wrote by hand becomes the one the app is watching rather than gaining
     * a duplicate beside it. That is also what makes the two-bills-one-task race real enough to test.
     *
     * **It carries forward.** A week that closes without the work being done mints a new row under a
     * new id; [carry] is that hop, and [state] follows it exactly as the real bridge does.
     */
    private class FakeWeek : BillWeek {
        val tasks = mutableMapOf<String, BillTasks.PublishedTask>()
        val carry = mutableMapOf<String, String>()
        val published = mutableListOf<Triple<String, LocalDate, String>>()
        val retired = mutableListOf<String>()
        var nextId = 1
        var refuse = false

        override suspend fun publish(title: String, due: LocalDate, note: String): String? {
            if (refuse) return null
            tasks.values.firstOrNull { it.title == title && it.open && !it.completed }
                ?.let { return it.id }
            val id = "task-${nextId++}"
            tasks[id] = BillTasks.PublishedTask(id, title, due, completed = false, completedAtMillis = null)
            published += Triple(title, due, note)
            return id
        }

        override suspend fun reschedule(taskId: String, due: LocalDate, title: String): Boolean {
            val existing = tasks[taskId] ?: return false
            tasks[taskId] = existing.copy(dueDate = due, title = title)
            return true
        }

        override suspend fun retire(taskId: String): Boolean {
            retired += taskId
            return tasks.remove(taskId) != null
        }

        override suspend fun state(taskId: String): BillTasks.PublishedTask? {
            var id = taskId
            var hops = 0
            while (carry.containsKey(id) && hops < 100) {
                id = carry.getValue(id)
                hops++
            }
            return tasks[id]
        }
    }

    /** Finance's store, reduced to the three things a round asks of it. */
    private class FakeStore(var snapshots: List<BillSnapshot>) : BillStore {
        val links = mutableMapOf<String, BillTasks.TaskLink>()
        val paid = mutableMapOf<String, LocalDate>()

        override suspend fun billSnapshots(now: Long) = snapshots
        override suspend fun setBillLink(billId: String, taskId: String?, publishedDue: LocalDate?) {
            links[billId] = BillTasks.TaskLink(taskId, publishedDue)
        }

        override suspend fun markPaidFromWeek(billId: String, paidOn: LocalDate): Boolean {
            paid[billId] = paidOn
            return true
        }
    }

    private fun snapshot(bill: Bills.Bill, link: BillTasks.TaskLink = BillTasks.TaskLink.NONE) =
        BillSnapshot(bill = bill, accountName = "Checking", link = link)

    private fun round(week: BillWeek, store: BillStore, onPublished: () -> Unit = {}) =
        BillRound(week, store, zone, onPublished)

    @Test
    fun `a due bill lands on the week, and the link is recorded`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(listOf(snapshot(bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"))))
        var announced = false

        val report = round(week, store) { announced = true }.run(now)

        assertEquals(1, report.published)
        assertEquals("Pay Rent — $900", week.published.single().first)
        assertEquals(LocalDate.parse("2026-09-12"), week.published.single().second)
        assertEquals(BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12")), store.links["b1"])
        assertTrue("the install has now published something", announced)
    }

    @Test
    fun `running twice does not publish twice`() = runTest {
        val week = FakeWeek()
        val bill = bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1")
        val store = FakeStore(listOf(snapshot(bill)))

        round(week, store).run(now)
        store.snapshots = listOf(snapshot(bill, store.links.getValue("b1")))
        val second = round(week, store).run(now)

        assertEquals(0, second.published)
        assertEquals(1, week.published.size)
    }

    @Test
    fun `a week that carried the task forward is followed to the row that exists now`() = runTest {
        val week = FakeWeek()
        // The old row is a fossil in a closed week; LifeOps minted "task-9" for the new one. The
        // link Finance stored points at the fossil, and the round has to re-point it — otherwise
        // every later round asks about a task nobody can tick.
        week.tasks["task-9"] = BillTasks.PublishedTask(
            id = "task-9", title = "Pay Rent — $900", dueDate = LocalDate.parse("2026-09-12"),
            completed = false, completedAtMillis = null
        )
        week.carry["task-1"] = "task-9"
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"),
                    BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
                )
            )
        )

        val report = round(week, store).run(now)
        assertEquals("task-9", store.links["b1"]?.taskId)
        // Following the hop is all that was needed; nothing was published, moved or retired.
        assertTrue(report.toString(), !report.didAnything)
        assertTrue(week.published.isEmpty())
    }

    @Test
    fun `a task somebody deleted is not put back`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"),
                    BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
                )
            )
        )

        val report = round(week, store).run(now)
        assertEquals(1, report.forgotten)
        // The date is remembered, which is what stops the task being put back on the next round.
        assertEquals(LocalDate.parse("2026-09-12"), store.links["b1"]?.publishedDue)
        assertNull(store.links["b1"]?.taskId)

        store.snapshots = listOf(snapshot(store.snapshots.single().bill, store.links.getValue("b1")))
        assertTrue(!round(week, store).run(now).didAnything)
        assertTrue(week.published.isEmpty())
    }

    @Test
    fun `a tick on the week marks the bill paid here`() = runTest {
        val week = FakeWeek()
        week.tasks["task-1"] = BillTasks.PublishedTask(
            id = "task-1", title = "Pay Rent — $900", dueDate = LocalDate.parse("2026-09-12"),
            completed = true, completedAtMillis = LocalDate.parse("2026-09-11").atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"),
                    BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
                )
            )
        )

        val report = round(week, store).run(now)
        assertEquals(1, report.paid)
        assertEquals(LocalDate.parse("2026-09-11"), store.paid["b1"])
        // The ticked task stays where it is: it is a true record, and removing it would erase
        // somebody's own week.
        assertTrue(week.retired.isEmpty())
    }

    @Test
    fun `a bill the bank settled takes its task back off the week`() = runTest {
        val week = FakeWeek()
        week.tasks["task-1"] = BillTasks.PublishedTask(
            id = "task-1", title = "Pay Rent — $900", dueDate = LocalDate.parse("2026-09-12"),
            completed = false, completedAtMillis = null
        )
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-12", amount = 900.0, paidOn = "2026-09-10", id = "b1"),
                    BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
                )
            )
        )

        val report = round(week, store).run(now)
        assertEquals(1, report.retired)
        assertEquals(listOf("task-1"), week.retired)
        // Both halves of the link go, so a bill switched back on publishes afresh.
        assertEquals(BillTasks.TaskLink(null, null), store.links["b1"])
    }

    @Test
    fun `two bills cannot share one task, and one tick cannot pay both`() = runTest {
        val week = FakeWeek()
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"),
                    BillTasks.TaskLink("task-shared", LocalDate.parse("2026-09-12"))
                ),
                snapshot(bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b2"))
            )
        )
        week.tasks["task-shared"] = BillTasks.PublishedTask(
            id = "task-shared", title = "Pay Rent — $900", dueDate = LocalDate.parse("2026-09-12"),
            completed = false, completedAtMillis = null
        )

        // Publishing adopts an open task of the same title — right when it is one you wrote by hand,
        // wrong when it already belongs to another bill. b2 has the same payee, date and amount, so
        // it generates the same title and the week hands back b1's task.
        val report = round(week, store).run(now)

        assertEquals("nothing new should reach the week", 0, report.published)
        // b1 keeps what it had; b2 goes without a task rather than sharing one, because a single
        // tick must not pay two bills.
        assertNull(store.links["b2"]?.taskId)
        assertEquals(LocalDate.parse("2026-09-12"), store.links["b2"]?.publishedDue)
    }

    @Test
    fun `a week that refuses the task still records the attempt, so the round stops trying`() = runTest {
        val week = FakeWeek().apply { refuse = true }
        val store = FakeStore(listOf(snapshot(bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "b1"))))

        val report = round(week, store).run(now)
        assertEquals(0, report.published)
        assertEquals(BillTasks.TaskLink(null, LocalDate.parse("2026-09-12")), store.links["b1"])
    }

    @Test
    fun `a moved due date reschedules rather than publishing a second task`() = runTest {
        val week = FakeWeek()
        week.tasks["task-1"] = BillTasks.PublishedTask(
            id = "task-1", title = "Pay Rent — $900", dueDate = LocalDate.parse("2026-09-12"),
            completed = false, completedAtMillis = null
        )
        val store = FakeStore(
            listOf(
                snapshot(
                    bill(payee = "Rent", due = "2026-09-15", amount = 900.0, id = "b1"),
                    BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
                )
            )
        )

        val report = round(week, store).run(now)
        assertEquals(1, report.rescheduled)
        assertEquals(0, report.published)
        assertEquals(LocalDate.parse("2026-09-15"), week.tasks.getValue("task-1").dueDate)
    }

    @Test
    fun `an empty week of bills is a round that did nothing and said so`() = runTest {
        val report = round(FakeWeek(), FakeStore(emptyList())).run(now)
        assertTrue(!report.didAnything)
    }
}
