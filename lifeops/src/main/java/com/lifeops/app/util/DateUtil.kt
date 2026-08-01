package com.lifeops.app.util

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object DateUtil {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // Single source of truth for where a week boundary sits: weeks are Monday-anchored in
    // the system zone. Both task weeks (currentWeekStart) and counter weekKeys (weekIndexFor)
    // route through weekStartFor, so they can never disagree on where a week begins.
    private val WEEK_ANCHOR: LocalDate = LocalDate.of(1970, 1, 5) // first Monday of the epoch

    fun now(): String = Instant.now().toString()

    /** Monday of the ISO week containing [date] — the one definition of a week boundary. */
    fun weekStartFor(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun currentWeekStart(): LocalDate = weekStartFor(LocalDate.now())

    fun currentWeekEnd(): LocalDate = currentWeekStart().plusDays(6)

    /**
     * Stable, monotonically increasing index of the Monday-anchored week containing [date].
     * Consecutive weeks differ by exactly 1. This is the only place the week-boundary floor
     * math lives — CounterEvent inserts stamp weekKey from an instant, catch-up close compares
     * week rows' startDate against it — so counters and tasks can never disagree on a boundary.
     */
    fun weekIndexFor(date: LocalDate): Int =
        ChronoUnit.WEEKS.between(WEEK_ANCHOR, weekStartFor(date)).toInt()

    /** Week index of the week containing [millis], interpreted in the system zone. */
    fun weekIndexFor(millis: Long): Int =
        weekIndexFor(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())

    /** Inverse of [weekIndexFor]: the Monday that starts the week with [index]. */
    fun weekStartForIndex(index: Int): LocalDate = WEEK_ANCHOR.plusWeeks(index.toLong())

    fun formatDate(date: String?): String {
        if (date == null) return ""
        return try {
            LocalDate.parse(date, dateFormatter).format(DateTimeFormatter.ofPattern("MMM d"))
        } catch (e: Exception) { date }
    }

    fun isOverdue(dueDate: String?): Boolean {
        if (dueDate == null) return false
        return try {
            LocalDate.parse(dueDate, dateFormatter).isBefore(LocalDate.now())
        } catch (e: Exception) { false }
    }

    fun epochMillisForDate(date: String, hour: Int, minute: Int = 0): Long {
        return try {
            val ld = LocalDate.parse(date, dateFormatter)
            val ldt = LocalDateTime.of(ld, LocalTime.of(hour, minute))
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    fun epochMillisForDayBefore(date: String, hour: Int): Long {
        return try {
            val ld = LocalDate.parse(date, dateFormatter).minusDays(1)
            val ldt = LocalDateTime.of(ld, LocalTime.of(hour, 0))
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    fun isoFromEpoch(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).toString()

    fun isValidDate(date: String): Boolean = try {
        LocalDate.parse(date, dateFormatter)
        true
    } catch (_: Exception) { false }

    fun sinceDate(daysAgo: Int): String {
        val date = LocalDate.now().minusDays(daysAgo.toLong())
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toString()
    }

    /** Local ISO date (yyyy-MM-dd) of the day containing [millis], in the system zone. */
    fun localDateKey(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /**
     * Local ISO date (yyyy-MM-dd) for a stored timestamp [iso], in the system zone.
     *
     * Timestamps are stored as UTC instants (see [now]/[isoFromEpoch]), so slicing the first
     * ten characters off the string yields the *UTC* calendar date — which rolls a late-evening
     * local record onto the next day. Converting to the system zone first keeps the date on the
     * day the user actually experienced it. A value that is already a plain date (yyyy-MM-dd) or
     * otherwise not a parseable instant falls back to its first ten characters unchanged.
     */
    fun localDateKey(iso: String): String = try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    } catch (_: Exception) { iso.take(10) }

    /** Local ISO date (yyyy-MM-dd) for today, in the system zone. */
    fun todayKey(): String = LocalDate.now().toString()

    /** Local hour-of-day (0-23) for [millis] in the system zone. */
    fun localHour(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    /** Format an ISO-8601 instant string as a local "MMM d, h:mm a"; echoes input on failure. */
    fun formatInstant(iso: String?): String {
        if (iso == null) return ""
        return try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
        } catch (_: Exception) { iso }
    }
}
