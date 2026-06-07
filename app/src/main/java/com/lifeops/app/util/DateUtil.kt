package com.lifeops.app.util

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object DateUtil {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun now(): String = Instant.now().toString()

    fun currentWeekStart(): LocalDate {
        val today = LocalDate.now()
        return today.with(DayOfWeek.MONDAY)
    }

    fun currentWeekEnd(): LocalDate = currentWeekStart().plusDays(6)

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

    fun sinceDate(daysAgo: Int): String {
        val date = LocalDate.now().minusDays(daysAgo.toLong())
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toString()
    }
}
