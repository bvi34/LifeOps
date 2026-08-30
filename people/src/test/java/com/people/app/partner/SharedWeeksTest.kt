package com.people.app.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SharedWeeksTest {

    @Test
    fun `a week begins on Monday, matching the week LifeOps plans`() {
        // Wednesday 2 September 2026 sits in the week beginning Monday 31 August.
        assertEquals(LocalDate.parse("2026-08-31"), SharedWeeks.startOf(LocalDate.parse("2026-09-02")))
        // A Monday is its own week's start, and a Sunday belongs to the week that has just run.
        assertEquals(LocalDate.parse("2026-08-31"), SharedWeeks.startOf(LocalDate.parse("2026-08-31")))
        assertEquals(LocalDate.parse("2026-08-31"), SharedWeeks.startOf(LocalDate.parse("2026-09-06")))
    }

    @Test
    fun `the window runs Monday to Sunday`() {
        val week = SharedWeeks.windowFor(LocalDate.parse("2026-09-02"))

        assertEquals("2026-08-31", week.weekStart)
        assertEquals("2026-09-06", week.weekEnd)
        assertTrue(week.tasks.isEmpty())
    }

    @Test
    fun `the seven days come back in order for the day-by-day view`() {
        val days = SharedWeeks.daysOf("2026-08-31")

        assertEquals(7, days.size)
        assertEquals(LocalDate.parse("2026-08-31"), days.first())
        assertEquals(LocalDate.parse("2026-09-06"), days.last())
    }

    @Test
    fun `a week we cannot parse yields no days rather than throwing`() {
        assertTrue(SharedWeeks.daysOf("").isEmpty())
        assertTrue(SharedWeeks.daysOf("not-a-date").isEmpty())
    }

    @Test
    fun `only this week is current`() {
        val today = LocalDate.parse("2026-09-02")

        assertTrue(SharedWeeks.isCurrent("2026-08-31", today))
        assertFalse(SharedWeeks.isCurrent("2026-08-24", today))
        assertFalse(SharedWeeks.isCurrent("2026-09-07", today))
    }
}
