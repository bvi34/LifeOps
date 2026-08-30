package com.people.app.partner

import com.people.app.partner.PartnerWeekDiff.ChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerWeekDiffTest {

    private fun task(
        id: String,
        title: String = "Book the van",
        due: String? = "2026-08-31",
        done: Boolean = false
    ) = SharedTask(taskId = id, title = title, dueDate = due, done = done)

    @Test
    fun `a task that was not there before is an addition`() {
        val changes = PartnerWeekDiff.diff(previous = emptyList(), incoming = listOf(task("t1")))

        assertEquals(ChangeKind.ADDED, changes.single().kind)
        assertEquals("Book the van", changes.single().title)
    }

    @Test
    fun `a task that has gone is a removal`() {
        val changes = PartnerWeekDiff.diff(previous = listOf(task("t1")), incoming = emptyList())

        assertEquals(ChangeKind.REMOVED, changes.single().kind)
    }

    @Test
    fun `a tick and an untick are told apart`() {
        assertEquals(
            ChangeKind.COMPLETED,
            PartnerWeekDiff.diff(listOf(task("t1", done = false)), listOf(task("t1", done = true))).single().kind
        )
        assertEquals(
            ChangeKind.REOPENED,
            PartnerWeekDiff.diff(listOf(task("t1", done = true)), listOf(task("t1", done = false))).single().kind
        )
    }

    @Test
    fun `a retitled or re-dated task is an edit`() {
        assertEquals(
            ChangeKind.EDITED,
            PartnerWeekDiff.diff(listOf(task("t1")), listOf(task("t1", title = "Book the big van"))).single().kind
        )
        assertEquals(
            ChangeKind.EDITED,
            PartnerWeekDiff.diff(listOf(task("t1")), listOf(task("t1", due = "2026-09-02"))).single().kind
        )
    }

    @Test
    fun `an unchanged week is not news`() {
        val week = listOf(task("t1"), task("t2", title = "Pick up keys", due = null))

        assertTrue(PartnerWeekDiff.diff(week, week).isEmpty())
    }

    @Test
    fun `the same week in a different order is still not news`() {
        val week = listOf(task("t1"), task("t2", title = "Pick up keys"))

        assertTrue(PartnerWeekDiff.diff(week, week.reversed()).isEmpty())
    }

    @Test
    fun `changes read down the week, with removals after what is still there`() {
        val previous = listOf(
            task("gone", title = "Cancelled thing", due = "2026-08-31"),
            task("keep", title = "Call the vet", due = "2026-09-03")
        )
        val incoming = listOf(
            task("keep", title = "Call the vet", due = "2026-09-03", done = true),
            task("new-late", title = "Late", due = "2026-09-04"),
            task("new-early", title = "Early", due = "2026-09-01")
        )

        val changes = PartnerWeekDiff.diff(previous, incoming)

        assertEquals(
            listOf("Early", "Call the vet", "Late", "Cancelled thing"),
            changes.map { it.title }
        )
        assertEquals(ChangeKind.REMOVED, changes.last().kind)
    }

    @Test
    fun `the week reads in day order with undated work last`() {
        val sorted = PartnerWeekDiff.sorted(
            listOf(
                task("c", title = "Whenever", due = null),
                task("b", title = "Wednesday", due = "2026-09-02"),
                task("a", title = "Monday", due = "2026-08-31")
            )
        )

        assertEquals(listOf("Monday", "Wednesday", "Whenever"), sorted.map { it.title })
    }
}
