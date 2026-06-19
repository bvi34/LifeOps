package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class DateUtilTest {

    // Start-of-day millis in the same zone weekIndexFor reads, so these tests are
    // deterministic regardless of the JVM's default timezone.
    private fun millisAtStartOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `weekIndexFor is constant within a Mon-Sun week and steps by one across weeks`() {
        val monday = LocalDate.of(2026, 6, 15).with(DayOfWeek.MONDAY) // normalize to that week's Monday
        val w = DateUtil.weekIndexFor(millisAtStartOfDay(monday))

        assertEquals(w, DateUtil.weekIndexFor(millisAtStartOfDay(monday.plusDays(3)))) // Thursday, same week
        assertEquals(w, DateUtil.weekIndexFor(millisAtStartOfDay(monday.plusDays(6)))) // Sunday, same week
        assertEquals(w + 1, DateUtil.weekIndexFor(millisAtStartOfDay(monday.plusDays(7)))) // next Monday
        assertEquals(w - 1, DateUtil.weekIndexFor(millisAtStartOfDay(monday.minusDays(1)))) // prior Sunday
    }

    @Test
    fun `weekIndexFor(LocalDate) matches the millis overload and steps by one per week`() {
        val monday = LocalDate.of(2026, 6, 15).with(DayOfWeek.MONDAY)
        assertEquals(DateUtil.weekIndexFor(millisAtStartOfDay(monday)), DateUtil.weekIndexFor(monday))
        assertEquals(DateUtil.weekIndexFor(monday) + 1, DateUtil.weekIndexFor(monday.plusWeeks(1)))
        // every day in a week maps to the same index as that week's Monday
        assertEquals(DateUtil.weekIndexFor(monday), DateUtil.weekIndexFor(monday.plusDays(5)))
    }

    @Test
    fun `weekIndexFor agrees with the task-week boundary (currentWeekStart)`() {
        // The current instant and the current task week's Monday must land in the same
        // week index — i.e. counters and tasks share one boundary.
        val viaInstant = DateUtil.weekIndexFor(System.currentTimeMillis())
        val viaTaskWeek = DateUtil.weekIndexFor(millisAtStartOfDay(DateUtil.currentWeekStart()))
        assertEquals(viaTaskWeek, viaInstant)
    }

    @Test
    fun `isValidDate accepts well-formed ISO dates`() {
        assertTrue(DateUtil.isValidDate("2026-06-10"))
        assertTrue(DateUtil.isValidDate("2024-02-29")) // 2024 is a leap year
        assertTrue(DateUtil.isValidDate("2000-01-01"))
    }

    @Test
    fun `isValidDate rejects malformed dates`() {
        assertFalse(DateUtil.isValidDate("2026-13-01"))  // invalid month
        assertFalse(DateUtil.isValidDate("2026-00-01"))  // month zero
        assertFalse(DateUtil.isValidDate("2025-02-29"))  // not a leap year
        assertFalse(DateUtil.isValidDate("not-a-date"))
        assertFalse(DateUtil.isValidDate(""))
        assertFalse(DateUtil.isValidDate("2026/06/10"))  // wrong separator
    }

    @Test
    fun `formatDate returns empty string for null`() {
        assertEquals("", DateUtil.formatDate(null))
    }

    @Test
    fun `formatDate returns raw string for unparseable input`() {
        val bad = "not-a-date"
        assertEquals(bad, DateUtil.formatDate(bad))
    }

    @Test
    fun `formatDate formats valid date correctly`() {
        assertEquals("Jun 10", DateUtil.formatDate("2026-06-10"))
    }

    @Test
    fun `isOverdue returns false for null`() {
        assertFalse(DateUtil.isOverdue(null))
    }
}
