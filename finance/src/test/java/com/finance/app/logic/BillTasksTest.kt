package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The decision the LifeOps seam is built on, tested one awkward case at a time.
 *
 * The awkward cases are the whole point: a task somebody deleted, a bill the bank settled by itself,
 * a week that closed and carried the task into a new row. Keeping the decision pure is what makes
 * them testable without a database or an emulator.
 */
class BillTasksTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val today = LocalDate.parse("2026-09-09")
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun decide(
        bill: Bills.Bill,
        link: BillTasks.TaskLink = BillTasks.TaskLink.NONE,
        task: BillTasks.PublishedTask? = null
    ) = BillTasks.decide(bill, accountName = "Checking", link = link, task = task, now = now, zone = zone)

    private fun published(
        id: String = "task-1",
        title: String = "Pay Rent — $900",
        due: String? = "2026-09-12",
        completed: Boolean = false,
        open: Boolean = true,
        completedAt: Long? = null
    ) = BillTasks.PublishedTask(
        id = id,
        title = title,
        dueDate = due?.let { LocalDate.parse(it) },
        completed = completed,
        completedAtMillis = completedAt,
        open = open
    )

    @Test
    fun `a bill inside the lead window is published`() {
        val action = decide(bill(payee = "Rent", due = "2026-09-12", amount = 900.0))
        assertTrue(action is BillTasks.Action.Publish)
        val publish = action as BillTasks.Action.Publish
        assertEquals(LocalDate.parse("2026-09-12"), publish.due)
        assertEquals("Pay Rent — $900", publish.title)
    }

    @Test
    fun `a bill beyond the lead window is nobody's problem yet`() {
        // Every week opening with next month's bills on it trains people to ignore the list.
        assertEquals(
            BillTasks.Action.Idle,
            decide(bill(payee = "Rent", due = "2026-10-01", amount = 900.0))
        )
    }

    @Test
    fun `the bank settling a bill takes it off the week, ticked or not`() {
        // This is the behaviour that makes the app worth having on the week at all: the list cleans
        // itself up from the account rather than from your memory.
        val paid = bill(payee = "Rent", due = "2026-09-12", amount = 900.0, paidOn = "2026-09-10")
        val action = decide(paid, link = BillTasks.TaskLink("task-1"), task = published())
        assertEquals(BillTasks.Action.Retire("task-1"), action)
    }

    @Test
    fun `a paid bill with no task needs nothing done about it`() {
        val paid = bill(payee = "Rent", due = "2026-09-12", amount = 900.0, paidOn = "2026-09-10")
        assertEquals(BillTasks.Action.Idle, decide(paid))
    }

    @Test
    fun `ticking it in LifeOps marks it paid here`() {
        val action = decide(
            bill(payee = "Rent", due = "2026-09-12", amount = 900.0),
            link = BillTasks.TaskLink("task-1"),
            task = published(completed = true, completedAt = now)
        )
        assertEquals(BillTasks.Action.MarkPaid("task-1", now), action)
    }

    @Test
    fun `a completed task with no timestamp falls back to now rather than to zero`() {
        val action = decide(
            bill(payee = "Rent", due = "2026-09-12", amount = 900.0),
            link = BillTasks.TaskLink("task-1"),
            task = published(completed = true, completedAt = null)
        ) as BillTasks.Action.MarkPaid
        assertEquals(now, action.completedAtMillis)
    }

    @Test
    fun `an autopaid bill does not ask you to pay it`() {
        val autopaid = bill(payee = "Netflix", due = "2026-09-12", amount = 15.49, publishToWeek = false)
        assertEquals(BillTasks.Action.Idle, decide(autopaid))
        assertEquals(
            BillTasks.Action.Retire("task-1"),
            decide(autopaid, link = BillTasks.TaskLink("task-1"), task = published())
        )
    }

    @Test
    fun `a re-read statement moves the task rather than adding a second one`() {
        val action = decide(
            bill(payee = "Rent", due = "2026-09-14", amount = 900.0),
            link = BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12")),
            task = published(due = "2026-09-12")
        )
        assertEquals(
            BillTasks.Action.Reschedule("task-1", LocalDate.parse("2026-09-14"), "Pay Rent — $900"),
            action
        )
    }

    @Test
    fun `an amount that changed rewrites the title, because the title carries the figure`() {
        val action = decide(
            bill(payee = "Rent", due = "2026-09-12", amount = 950.0),
            link = BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12")),
            task = published(title = "Pay Rent — $900")
        )
        assertEquals(
            BillTasks.Action.Reschedule("task-1", LocalDate.parse("2026-09-12"), "Pay Rent — $950"),
            action
        )
    }

    @Test
    fun `nothing happens when everything already agrees`() {
        assertEquals(
            BillTasks.Action.Idle,
            decide(
                bill(payee = "Rent", due = "2026-09-12", amount = 900.0),
                link = BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12")),
                task = published()
            )
        )
    }

    @Test
    fun `a task you deleted is not put back`() {
        // An app that silently re-adds what you just deleted is an app you start deleting from twice.
        val bill = bill(payee = "Rent", due = "2026-09-12", amount = 900.0)
        val link = BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12"))
        assertEquals(BillTasks.Action.Forget, decide(bill, link = link, task = null))
        // And the round that follows, with the link dropped but the date remembered, leaves it alone.
        val afterForget = BillTasks.TaskLink(null, LocalDate.parse("2026-09-12"))
        assertEquals(BillTasks.Action.Idle, decide(bill, link = afterForget, task = null))
    }

    @Test
    fun `a task stranded in a closed week is let go of rather than revived`() {
        val action = decide(
            bill(payee = "Rent", due = "2026-09-12", amount = 900.0),
            link = BillTasks.TaskLink("task-1", LocalDate.parse("2026-09-12")),
            task = published(open = false)
        )
        assertEquals(BillTasks.Action.Forget, action)
    }

    @Test
    fun `a prediction says about, and a statement does not`() {
        val predicted = bill(
            payee = "City Utilities", due = "2026-09-12", amount = 180.0,
            source = Bills.Source.PREDICTED
        )
        val statement = bill(
            payee = "USAA Visa", due = "2026-09-12", amount = 1_240.0,
            source = Bills.Source.STATEMENT, minimum = 35.0
        )
        assertEquals("Pay City Utilities — about $180", BillTasks.title(predicted, "Checking"))
        assertEquals("Pay USAA Visa — $1,240", BillTasks.title(statement, "Checking"))
    }

    @Test
    fun `the note says where the figure came from`() {
        val statement = bill(
            payee = "USAA Visa", due = "2026-09-12", amount = 1_240.0,
            source = Bills.Source.STATEMENT, minimum = 35.0
        )
        val note = BillTasks.note(statement, "USAA Rewards Visa")
        assertTrue(note, note.startsWith("From your USAA Rewards Visa statement."))
        assertTrue(note, note.contains("minimum $35.00"))
        assertTrue(note, note.contains("tick this off by itself"))

        val guess = BillTasks.note(
            bill(payee = "Gym", due = "2026-09-12", amount = 45.0, source = Bills.Source.PREDICTED),
            "Checking"
        )
        assertTrue(guess, guess.startsWith("Predicted from your history"))
    }
}
