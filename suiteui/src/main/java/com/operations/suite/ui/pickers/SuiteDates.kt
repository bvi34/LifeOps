package com.operations.suite.ui.pickers

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The suite's date vocabulary: how a day is written, and how it converts to whatever the app
 * storing it happens to hold.
 *
 * This exists because the same three lines of conversion had been written four times, each with a
 * slightly different answer to the same trap: **Material's date picker works in UTC midnight, and a
 * calendar date is not an instant.** Read a picker's millis back in the local zone and a household
 * west of Greenwich gets yesterday. Every conversion in the suite now goes through [fromPickerMillis]
 * and [toPickerMillis], which are explicit about it, and the pickers themselves hand callers a
 * [LocalDate] so the question mostly stops arising.
 */
object SuiteDates {

    private val dayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val shortDayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dayTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())

    /** "2 Sep 2026" — a date the reader has to be sure of. */
    fun formatDay(date: LocalDate): String = dayFormat.format(date)

    /** "2 Sep" — a date in a list whose year is already obvious from context. */
    fun formatShortDay(date: LocalDate): String = shortDayFormat.format(date)

    fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun formatDayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayTimeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    /**
     * Today's instants read as a time; older ones carry their date, because "14:20" on its own
     * lies about how old a reading is.
     */
    fun formatStamp(
        millis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val then = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return when {
            then == today -> formatTime(millis, zone)
            then == today.minusDays(1) -> "Yesterday ${formatTime(millis, zone)}"
            else -> formatDayTime(millis, zone)
        }
    }

    /** `"2026-09-02"` -> the date, or null if it isn't one. Never throws; text fields feed this. */
    fun parseIso(text: String?): LocalDate? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The date as `"2026-09-02"`, the form every app in the suite stores a plain day as. */
    fun toIso(date: LocalDate): String = date.toString()

    /** Local midnight, for an app that stores a day as an instant it will later do arithmetic on. */
    fun toEpochMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The calendar day an instant falls on, locally. */
    fun toLocalDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** What Material's `DatePickerState` wants: the date as UTC midnight. */
    fun toPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** What Material's `DatePickerState` gives back: UTC midnight, read as the day that was tapped. */
    fun fromPickerMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /** A date and a wall-clock time, together, as the instant they name locally. */
    fun toEpochMillis(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    /** Today, as the local-midnight millis an app that stores days as instants would hold. */
    fun todayMillis(zone: ZoneId = ZoneId.systemDefault()): Long = toEpochMillis(LocalDate.now(zone), zone)
}
