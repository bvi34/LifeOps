package com.lifeops.app.util

import java.time.LocalDate

/**
 * Pure, Android-free decision logic for recurring-task seeding. Kept out of the repository so it
 * is unit-testable on the JVM (same rationale as the growth/weather util modules).
 *
 * A recurring series is anchored on its **most recent existing instance**. Because LifeOps seeds
 * recurrence one consecutive week at a time and skips off-weeks (no instance is created), the last
 * instance stays put until the next on-week, which keeps interval spacing exact without any stored
 * anchor date.
 *
 * Two modes, mirroring the columns on TaskEntity:
 *  • Week-interval — [isWeeklyIntervalDue]: reappears every N weeks.
 *  • Monthly-by-date — [weekContainsMonthlyDay]: reappears in the week that contains a calendar day.
 */
object Recurrence {

    /**
     * Week-interval cadence. Due when the target week is a positive whole-multiple of
     * [intervalWeeks] weeks after the last instance's week. Weekly (interval 1) is due every week;
     * bi-weekly (2) every other week, etc. [intervalWeeks] is coerced to at least 1 so a legacy or
     * malformed 0 (e.g. from a pre-feature backup) behaves as weekly rather than dividing by zero.
     */
    fun isWeeklyIntervalDue(lastInstanceWeekIndex: Int, targetWeekIndex: Int, intervalWeeks: Int): Boolean {
        val interval = intervalWeeks.coerceAtLeast(1)
        val delta = targetWeekIndex - lastInstanceWeekIndex
        return delta > 0 && delta % interval == 0
    }

    /**
     * Monthly-by-date cadence. True when the calendar day [dayOfMonth] falls inside the week
     * [weekStart]..[weekEnd] (inclusive). A week can straddle two months, so every month the week
     * touches is checked; [dayOfMonth] is clamped to each month's length, so 29–31 land on the last
     * day of shorter months (e.g. the 31st fires on Feb 28/29). Since a given calendar day lands in
     * exactly one Monday-anchored week per month, this fires exactly once per month per series.
     */
    fun weekContainsMonthlyDay(weekStart: LocalDate, weekEnd: LocalDate, dayOfMonth: Int): Boolean {
        var monthCursor = weekStart.withDayOfMonth(1)
        val lastMonth = weekEnd.withDayOfMonth(1)
        while (!monthCursor.isAfter(lastMonth)) {
            val day = dayOfMonth.coerceIn(1, monthCursor.lengthOfMonth())
            val target = monthCursor.withDayOfMonth(day)
            if (!target.isBefore(weekStart) && !target.isAfter(weekEnd)) return true
            monthCursor = monthCursor.plusMonths(1)
        }
        return false
    }
}
