package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class SuiteWhenTest {

    private val today = LocalDate.of(2026, 11, 12) // a Thursday

    @Test
    fun nearby_days_read_as_words() {
        assertEquals("Today", SuiteWhen.dayLabel(today, today))
        assertEquals("Tomorrow", SuiteWhen.dayLabel(today.plusDays(1), today))
        assertEquals("Yesterday", SuiteWhen.dayLabel(today.minusDays(1), today))
        assertEquals("4 days late", SuiteWhen.dayLabel(today.minusDays(4), today))
    }

    @Test
    fun this_week_is_a_weekday_and_later_is_a_date() {
        val weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        assertEquals(today.plusDays(3).format(weekday), SuiteWhen.dayLabel(today.plusDays(3), today))
        assertEquals(today.plusDays(7).format(dayMonth), SuiteWhen.dayLabel(today.plusDays(7), today))
    }
}
