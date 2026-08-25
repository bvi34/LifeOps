package com.people.app.logic

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** What kind of recurring date this is. Free text on the wire; these are the ones People offers. */
enum class DateKind(val key: String, val label: String) {
    BIRTHDAY("birthday", "Birthday"),
    ANNIVERSARY("anniversary", "Anniversary"),
    APPOINTMENT("appointment", "Yearly appointment"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): DateKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** A recurring date, resolved against a particular day. */
data class UpcomingDate(
    val label: String,
    val kind: DateKind,
    val personId: String,
    val next: LocalDate,
    val daysUntil: Long,
    /** The age being turned, when the original year is known. Null for "every 2 April". */
    val turning: Int?
) {
    val isToday: Boolean get() = daysUntil == 0L
}

/**
 * Recurring dates, framework-free.
 *
 * The whole difficulty here is the one day a year that doesn't exist. A 29 February birthday has no
 * anniversary in three years out of four, and every implementation that forgets this either throws
 * or silently drops the person from the list — which is exactly the person you least want to forget.
 * [nextOccurrence] moves such a date to 28 February in common years, the convention most people use
 * when asked, rather than 1 March.
 */
object ImportantDates {

    /** Parse `MM-dd`, or null if it isn't one. */
    fun parseMonthDay(monthDay: String?): Pair<Int, Int>? {
        val text = monthDay?.trim().orEmpty()
        val parts = text.split('-')
        if (parts.size != 2) return null
        val month = parts[0].toIntOrNull() ?: return null
        val day = parts[1].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        // Reject 31 April and friends, but keep 29 February — it is a real date in leap years.
        val longestForMonth = LocalDate.of(2020, month, 1).lengthOfMonth()
        if (day > longestForMonth) return null
        return month to day
    }

    /** `MM-dd` for an ISO `yyyy-MM-dd` birth date, so a person's birthday is a date like any other. */
    fun monthDayOf(isoDate: String?): String? {
        val date = parseIso(isoDate) ?: return null
        return "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }

    fun parseIso(isoDate: String?): LocalDate? {
        val text = isoDate?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * The next time [monthDay] comes round on or after [from]. Today counts as next — a birthday is
     * not "in 365 days" while it is still happening.
     */
    fun nextOccurrence(monthDay: String, from: LocalDate): LocalDate? {
        val (month, day) = parseMonthDay(monthDay) ?: return null
        val thisYear = clampedTo(from.year, month, day)
        return if (!thisYear.isBefore(from)) thisYear else clampedTo(from.year + 1, month, day)
    }

    /** The date in [year], clamped to the month's real length (29 Feb → 28 Feb in a common year). */
    private fun clampedTo(year: Int, month: Int, day: Int): LocalDate {
        val length = LocalDate.of(year, month, 1).lengthOfMonth()
        return LocalDate.of(year, month, minOf(day, length))
    }

    /**
     * Resolve one recurring date. [year] is the year it first happened, when known, and is what
     * turns "Ellie's birthday" into "Ellie turns 8".
     */
    fun resolve(
        personId: String,
        label: String,
        kind: DateKind,
        monthDay: String,
        year: Int?,
        from: LocalDate
    ): UpcomingDate? {
        val next = nextOccurrence(monthDay, from) ?: return null
        val turning = year?.takeIf { it in 1..next.year }?.let { next.year - it }
        return UpcomingDate(
            label = label,
            kind = kind,
            personId = personId,
            next = next,
            daysUntil = ChronoUnit.DAYS.between(from, next),
            turning = turning
        )
    }

    /** Everything coming up within [withinDays], soonest first. */
    fun upcoming(dates: List<UpcomingDate>, withinDays: Long = 60): List<UpcomingDate> =
        dates.filter { it.daysUntil in 0..withinDays }.sortedBy { it.daysUntil }

    /** "today" / "tomorrow" / "in 9 days" / "in 3 weeks" — how far off it reads to a person. */
    fun describe(daysUntil: Long): String = when {
        daysUntil < 0 -> "past"
        daysUntil == 0L -> "today"
        daysUntil == 1L -> "tomorrow"
        daysUntil < 14 -> "in $daysUntil days"
        daysUntil < 60 -> "in ${(daysUntil + 3) / 7} weeks"
        else -> "in ${(daysUntil + 15) / 30} months"
    }
}
