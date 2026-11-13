package com.operations.suitekit

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A date as a person reads it on a small surface — a home-screen widget row, a tile: "Today",
 * "Tomorrow", a weekday within the week, the date after that, and how late something is once it has
 * passed. One wording for every app's widget, so "Fri" means the same thing on all of them.
 */
object SuiteWhen {

    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    fun dayLabel(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days < -1 -> "${-days} days late"
            days == -1L -> "Yesterday"
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days < 7 -> date.format(WEEKDAY)
            else -> date.format(DAY_MONTH)
        }
    }
}
