package com.health.app.logic

import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Age from a birth date, framework-free.
 *
 * Health needs this for one reason that matters and one that doesn't. The one that matters: fever
 * advice is age-dependent — the same 38.2 °C is "watch it" in an adult and "call someone now" in a
 * six-week-old (see [Fever]). The one that doesn't: showing "4 mo" next to a profile name.
 *
 * Birth dates are stored as plain ISO `yyyy-MM-dd` strings — a birth date is a calendar fact, not an
 * instant, and storing it as millis makes it drift across time zones.
 */
object Age {

    /** Parse an ISO `yyyy-MM-dd` birth date, or null if it's absent or malformed. */
    fun parse(birthDate: String?): LocalDate? {
        val text = birthDate?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Whole months lived at [nowMillis], or null when the birth date is unknown or in the future. */
    fun monthsAt(birthDate: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val born = parse(birthDate) ?: return null
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        if (born.isAfter(today)) return null
        val period = Period.between(born, today)
        return period.years * 12 + period.months
    }

    /** Whole years lived at [nowMillis], or null when unknown. */
    fun yearsAt(birthDate: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int? =
        monthsAt(birthDate, nowMillis, zone)?.let { it / 12 }

    /**
     * A short human label: days under a month, months under two years, years after that. Under two,
     * months are the unit people actually use — and the unit the fever rules are written in.
     */
    fun describe(birthDate: String?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val born = parse(birthDate) ?: return null
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        if (born.isAfter(today)) return null
        val months = monthsAt(birthDate, nowMillis, zone) ?: return null
        return when {
            months < 1 -> {
                val days = java.time.temporal.ChronoUnit.DAYS.between(born, today).toInt()
                if (days == 1) "1 day" else "$days days"
            }
            months < 24 -> if (months == 1) "1 mo" else "$months mo"
            else -> "${months / 12} yr"
        }
    }
}
