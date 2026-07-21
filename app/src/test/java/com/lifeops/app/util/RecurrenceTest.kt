package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {

    // --- Week-interval cadence -------------------------------------------------------------

    @Test
    fun `weekly is due every week`() {
        assertTrue(Recurrence.isWeeklyIntervalDue(lastInstanceWeekIndex = 100, targetWeekIndex = 101, intervalWeeks = 1))
        assertTrue(Recurrence.isWeeklyIntervalDue(100, 102, 1))
    }

    @Test
    fun `bi-weekly skips the off-week and fires on the on-week`() {
        // Anchored on the last instance (week 100): week 101 is off, week 102 is on.
        assertFalse(Recurrence.isWeeklyIntervalDue(100, 101, 2))
        assertTrue(Recurrence.isWeeklyIntervalDue(100, 102, 2))
        assertFalse(Recurrence.isWeeklyIntervalDue(100, 103, 2))
        assertTrue(Recurrence.isWeeklyIntervalDue(100, 104, 2))
    }

    @Test
    fun `every-3-weeks fires only on multiples`() {
        assertFalse(Recurrence.isWeeklyIntervalDue(10, 11, 3))
        assertFalse(Recurrence.isWeeklyIntervalDue(10, 12, 3))
        assertTrue(Recurrence.isWeeklyIntervalDue(10, 13, 3))
    }

    @Test
    fun `never due for the anchor week or a past week`() {
        assertFalse(Recurrence.isWeeklyIntervalDue(100, 100, 1))
        assertFalse(Recurrence.isWeeklyIntervalDue(100, 99, 1))
    }

    @Test
    fun `interval below one behaves as weekly rather than dividing by zero`() {
        // A legacy/backup value of 0 must not crash and must reappear every week.
        assertTrue(Recurrence.isWeeklyIntervalDue(100, 101, 0))
        assertTrue(Recurrence.isWeeklyIntervalDue(100, 102, 0))
    }

    // --- Monthly-by-date cadence -----------------------------------------------------------

    private fun week(monday: String) = LocalDate.parse(monday) to LocalDate.parse(monday).plusDays(6)

    @Test
    fun `monthly fires in the week containing the target day`() {
        // Week of Mon 2026-01-12 .. Sun 2026-01-18 contains the 15th.
        val (start, end) = week("2026-01-12")
        assertTrue(Recurrence.weekContainsMonthlyDay(start, end, 15))
    }

    @Test
    fun `monthly does not fire in a week that misses the target day`() {
        // Week of Mon 2026-01-19 .. Sun 2026-01-25 does not contain the 15th.
        val (start, end) = week("2026-01-19")
        assertFalse(Recurrence.weekContainsMonthlyDay(start, end, 15))
    }

    @Test
    fun `monthly fires exactly once across a set of consecutive weeks`() {
        // Every Monday-anchored week of Jan 2026; the 15th should land in exactly one.
        val mondays = listOf("2025-12-29", "2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26")
        val hits = mondays.count { m ->
            val (s, e) = week(m)
            Recurrence.weekContainsMonthlyDay(s, e, 15)
        }
        assertEquals(1, hits)
    }

    @Test
    fun `day 31 clamps to the last day in a short month`() {
        // February 2026 has 28 days; the 31st should fire in the week containing Feb 28.
        val (start, end) = week("2026-02-23") // Mon 2026-02-23 .. Sun 2026-03-01
        assertTrue(Recurrence.weekContainsMonthlyDay(start, end, 31))
    }

    @Test
    fun `month-spanning week handles both months`() {
        // Mon 2026-06-29 .. Sun 2026-07-05 straddles June and July. Day 1 falls on Jul 1 (in range).
        val (start, end) = week("2026-06-29")
        assertTrue(Recurrence.weekContainsMonthlyDay(start, end, 1))
        // Day 30 falls on Jun 30 (also in this week).
        assertTrue(Recurrence.weekContainsMonthlyDay(start, end, 30))
        // Day 15 falls in neither month's slice of this week.
        assertFalse(Recurrence.weekContainsMonthlyDay(start, end, 15))
    }
}
