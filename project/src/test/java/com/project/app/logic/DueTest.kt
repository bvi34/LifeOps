package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The one date in Project, read against a day.
 *
 * Everything here is decided against a `today` passed in rather than against the clock, which is
 * what makes "overdue by 3 days" a thing that can be tested at all — and, on the app side, what
 * stops a lane disagreeing with itself as it scrolls past midnight.
 */
class DueTest {

    private val today = LocalDate.of(2026, 3, 14)

    private fun card(id: String, columnId: String, dueOn: LocalDate?, title: String = id) =
        BoardCard(
            id = id,
            columnId = columnId,
            title = title,
            notes = null,
            sortOrder = 0,
            dueOn = dueOn?.toEpochDay()
        )

    // ------------------------------------------------------------------ standing

    @Test
    fun `no date has no standing at all`() {
        assertNull(Due.standing(null, today))
    }

    @Test
    fun `a date reads as overdue, today, soon or later`() {
        fun stateOn(daysAway: Long) = Due.standing(today.plusDays(daysAway), today)?.state

        assertEquals(DueState.OVERDUE, stateOn(-1))
        assertEquals(DueState.OVERDUE, stateOn(-40))
        assertEquals(DueState.TODAY, stateOn(0))
        assertEquals(DueState.SOON, stateOn(1))
        // The boundary, both sides: a week away is still soon, eight days is not.
        assertEquals(DueState.SOON, stateOn(Due.SOON_DAYS))
        assertEquals(DueState.LATER, stateOn(Due.SOON_DAYS + 1))
    }

    @Test
    fun `a finished card is never late, however late it was`() {
        // Leaving it red would mean the board carried a permanent accusation about work that is
        // already behind you.
        val standing = Due.standing(today.minusDays(30), today, done = true)

        assertEquals(DueState.DONE, standing?.state)
        assertEquals("Was due 12 Feb", standing?.label)
    }

    @Test
    fun `days away is counted from today, and signed`() {
        assertEquals(0L, Due.standing(today, today)?.daysAway)
        assertEquals(3L, Due.standing(today.plusDays(3), today)?.daysAway)
        assertEquals(-3L, Due.standing(today.minusDays(3), today)?.daysAway)
    }

    // ------------------------------------------------------------------ the words

    @Test
    fun `near dates are counted in days and far ones are given as a date`() {
        fun labelOn(daysAway: Long) = Due.standing(today.plusDays(daysAway), today)?.label

        assertEquals("Due today", labelOn(0))
        assertEquals("Due tomorrow", labelOn(1))
        assertEquals("Due in 4 days", labelOn(4))
        // Past a week, "in 74 days" is a number nobody converts back into a day of the year.
        assertEquals("Due 27 May", labelOn(74))
    }

    @Test
    fun `one day late is not one days late`() {
        assertEquals("Overdue by a day", Due.standing(today.minusDays(1), today)?.label)
        assertEquals("Overdue by 3 days", Due.standing(today.minusDays(3), today)?.label)
    }

    @Test
    fun `the month name does not depend on where the phone is`() {
        // `logic/` spells its months out rather than taking a formatter, so a test that passes in
        // London passes in Berlin. This is the assertion that would catch someone "simplifying"
        // that back into a locale-aware formatter.
        (1..12).forEach { month ->
            val date = LocalDate.of(2026, month, 20)
            val label = Due.standing(date, date.minusDays(60))?.label
            assertTrue("month $month read as $label", label!!.startsWith("Due 20 "))
        }
        assertEquals("Due 20 Dec", Due.standing(LocalDate.of(2026, 12, 20), today)?.label)
    }

    // ------------------------------------------------------------------ what Project makes of a choice

    @Test
    fun `no date is ever refused, and one that has gone is remarked on`() {
        // People write down deadlines they have already missed — that is how a board comes to
        // reflect reality rather than the plan somebody had in January.
        assertTrue(Due.opinionOf(today.minusDays(1), today) is DueOpinion.Remark)
        assertEquals(DueOpinion.Fine, Due.opinionOf(today, today))
        assertEquals(DueOpinion.Fine, Due.opinionOf(today.plusDays(1), today))
        assertEquals(DueOpinion.Fine, Due.opinionOf(null, today))
    }

    // ------------------------------------------------------------------ overdue

    @Test
    fun `overdue is what has passed, is not finished, and is reported soonest first`() {
        val cards = listOf(
            card("late-2", "doing", today.minusDays(2)),
            card("late-9", "doing", today.minusDays(9)),
            card("today", "doing", today),
            card("future", "doing", today.plusDays(5)),
            card("undated", "doing", null),
            card("late-but-done", "done", today.minusDays(20))
        )

        val overdue = Due.overdue(cards, doneColumnIds = setOf("done"), today = today)

        assertEquals(listOf("late-9", "late-2"), overdue.map { it.id })
    }

    @Test
    fun `a card due today is not overdue`() {
        // The off-by-one that would have somebody chased at breakfast for work they have all day
        // to do.
        assertTrue(Due.overdue(listOf(card("t", "doing", today)), emptySet(), today).isEmpty())
    }

    @Test
    fun `two cards late on the same day come back in a defined order`() {
        val cards = listOf(
            card("b", "doing", today.minusDays(3), title = "Beta"),
            card("a", "doing", today.minusDays(3), title = "alpha")
        )

        // Ordering has to be total, or the banner counting them could list them differently on
        // every recomposition.
        assertEquals(listOf("a", "b"), Due.overdue(cards, emptySet(), today).map { it.id })
    }

    @Test
    fun `an epoch day is read back as the day it was`() {
        assertEquals(today, Due.dateOf(today.toEpochDay()))
        assertNull(Due.dateOf(null))
    }
}
