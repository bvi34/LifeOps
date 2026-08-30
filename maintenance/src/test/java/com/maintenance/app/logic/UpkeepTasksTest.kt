package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class UpkeepTasksTest {

    private val day = 86_400_000L
    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-03-01T00:00:00Z, so "today" and the arithmetic below are readable. */
    private val now = LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val today: LocalDate = LocalDate.of(2026, 3, 1)

    private fun plan(
        active: Boolean = true,
        publish: Boolean = true,
        everyDays: Int? = 90,
        everyMeter: Long? = null
    ) = UpkeepPlan(
        id = "plan-1",
        assetId = "asset-1",
        title = "Oil change",
        everyDays = everyDays,
        everyMeter = everyMeter,
        createdAt = now,
        active = active,
        publishToLifeOps = publish
    )

    private fun verdict(
        status: DueStatus = DueStatus.SCHEDULED,
        dueAt: Long? = now + 30 * day,
        summary: String = "Due in 4 weeks"
    ) = DueVerdict(status = status, dueAt = dueAt, summary = summary)

    private fun task(
        id: String = "task-1",
        title: String = "Truck: Oil change",
        due: LocalDate? = LocalDate.of(2026, 3, 31),
        completed: Boolean = false,
        completedAt: Long? = null,
        open: Boolean = true
    ) = UpkeepTasks.PublishedTask(id, title, due, completed, completedAt, open)

    private fun decide(
        plan: UpkeepPlan = plan(),
        verdict: DueVerdict = verdict(),
        link: UpkeepTasks.TaskLink = UpkeepTasks.TaskLink.NONE,
        task: UpkeepTasks.PublishedTask? = null
    ) = UpkeepTasks.decide(plan, "Truck", verdict, link, task, now, zone, MeterUnit.MILES)

    @Test
    fun `a plan with a due date puts itself on the week`() {
        val action = decide()

        val publish = action as UpkeepTasks.Action.Publish
        assertEquals(LocalDate.of(2026, 3, 31), publish.due)
        assertEquals("Truck: Oil change", publish.title)
        assertTrue(publish.note.contains("every 3 months"))
        assertTrue(publish.note.contains("Due in 4 weeks"))
    }

    @Test
    fun `the tick comes back as the one thing that outranks everything else`() {
        val completedAt = now - day
        val action = decide(
            // Even with the plan paused and the schedule gone, a completed task is still a fact.
            plan = plan(active = false),
            link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 3, 31)),
            task = task(completed = true, completedAt = completedAt)
        )

        assertEquals(UpkeepTasks.Action.MarkDone("task-1", completedAt), action)
    }

    @Test
    fun `a completed task with no timestamp is taken as done now rather than never`() {
        val action = decide(task = task(completed = true, completedAt = null))

        assertEquals(UpkeepTasks.Action.MarkDone("task-1", now), action)
    }

    @Test
    fun `a plan that stops wanting a task takes it off the week`() {
        val link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 3, 31))

        assertEquals(UpkeepTasks.Action.Retire("task-1"), decide(plan = plan(active = false), link = link, task = task()))
        assertEquals(UpkeepTasks.Action.Retire("task-1"), decide(plan = plan(publish = false), link = link, task = task()))
        // No interval at all: dormant, and nothing to date a task with.
        assertEquals(
            UpkeepTasks.Action.Retire("task-1"),
            decide(
                plan = plan(everyDays = null),
                verdict = verdict(status = DueStatus.DORMANT, dueAt = null),
                link = link,
                task = task()
            )
        )
    }

    @Test
    fun `a plan that never wanted a task is left alone`() {
        assertEquals(UpkeepTasks.Action.Idle, decide(plan = plan(publish = false)))
        assertEquals(
            UpkeepTasks.Action.Idle,
            decide(verdict = verdict(status = DueStatus.NEEDS_BASELINE, dueAt = null))
        )
    }

    @Test
    fun `a task somebody deleted is forgotten, not put back`() {
        val link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 3, 31))

        assertEquals(UpkeepTasks.Action.Forget, decide(link = link, task = null))

        // …and the next pass, with the link dropped but the occurrence remembered, leaves it alone.
        val afterForget = UpkeepTasks.TaskLink(taskId = null, publishedDue = LocalDate.of(2026, 3, 31))
        assertEquals(UpkeepTasks.Action.Idle, decide(link = afterForget, task = null))
    }

    @Test
    fun `once the plan moves on, a deleted task no longer holds it back`() {
        val afterForget = UpkeepTasks.TaskLink(taskId = null, publishedDue = LocalDate.of(2026, 3, 31))

        val action = decide(
            verdict = verdict(dueAt = now + 120 * day, summary = "Due in 4 months"),
            link = afterForget
        )

        assertEquals(LocalDate.of(2026, 6, 29), (action as UpkeepTasks.Action.Publish).due)
    }

    @Test
    fun `a date that moves under an open task reschedules it rather than adding a second`() {
        val action = decide(
            verdict = verdict(dueAt = now + 10 * day),
            link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 3, 31)),
            task = task(due = LocalDate.of(2026, 3, 31))
        )

        assertEquals(
            UpkeepTasks.Action.Reschedule("task-1", LocalDate.of(2026, 3, 11), "Truck: Oil change"),
            action
        )
    }

    @Test
    fun `renaming the asset or the job fixes the task's title in place`() {
        val action = decide(task = task(title = "Truck: Oil chnge"))

        assertEquals(
            UpkeepTasks.Action.Reschedule("task-1", LocalDate.of(2026, 3, 31), "Truck: Oil change"),
            action
        )
    }

    @Test
    fun `a task that already says the right thing is left alone`() {
        assertEquals(UpkeepTasks.Action.Idle, decide(task = task()))
    }

    @Test
    fun `an overdue plan with nothing to date it lands on today`() {
        // A mileage interval with no rate behind it: overdue, but there is no date in the verdict.
        val action = decide(
            plan = plan(everyDays = null, everyMeter = 5_000),
            verdict = verdict(status = DueStatus.OVERDUE, dueAt = null, summary = "Overdue by 200 mi")
        )

        val publish = action as UpkeepTasks.Action.Publish
        assertEquals(today, publish.due)
        assertTrue(publish.note.contains("every 5,000 mi"))
    }

    @Test
    fun `a plan that can't be dated today keeps the task it already has`() {
        // A mileage interval whose rate has become unknowable — a meter replaced, one reading left
        // to measure from. There is no date to publish for, but the job has not gone away.
        val action = decide(
            plan = plan(everyDays = null, everyMeter = 5_000),
            verdict = verdict(status = DueStatus.SCHEDULED, dueAt = null, summary = "Due in 200 mi"),
            link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 3, 31)),
            task = task()
        )

        assertEquals(UpkeepTasks.Action.Idle, action)
    }

    @Test
    fun `a task stranded in a closed week is published again on this one`() {
        val action = decide(
            verdict = verdict(status = DueStatus.OVERDUE, dueAt = now - 5 * day, summary = "Overdue by 5 days"),
            link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 2, 24)),
            task = task(due = LocalDate.of(2026, 2, 24), open = false)
        )

        assertEquals(LocalDate.of(2026, 2, 24), (action as UpkeepTasks.Action.Publish).due)
    }

    @Test
    fun `a stranded task is never deleted to tidy up - that week has already been reviewed`() {
        val link = UpkeepTasks.TaskLink("task-1", LocalDate.of(2026, 2, 24))

        assertEquals(
            UpkeepTasks.Action.Forget,
            decide(plan = plan(active = false), link = link, task = task(open = false))
        )
    }

    @Test
    fun `the note says where the task came from and what ticking it does`() {
        val note = UpkeepTasks.note(
            plan(everyDays = 180, everyMeter = 5_000),
            verdict(summary = "Due in 200 mi"),
            MeterUnit.MILES
        )

        assertEquals(
            "From Maintenance — every 5,000 mi or every 6 months. Due in 200 mi. " +
                "Ticking this here logs it there and starts the next one.",
            note
        )
    }

    @Test
    fun `the asset leads the title, because a week list is read across a dozen unrelated things`() {
        assertEquals("Truck: Oil change", UpkeepTasks.title("Truck", plan()))
        assertEquals("Truck: Oil change", UpkeepTasks.title("  Truck  ", plan()))
    }
}
