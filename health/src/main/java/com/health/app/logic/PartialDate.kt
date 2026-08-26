package com.health.app.logic

import java.time.LocalDate

/**
 * A date recorded at whatever precision somebody actually knew.
 *
 * Health has three places where a date arrives half-remembered rather than read off a clock: when a
 * condition started, when an allergy was first noticed, and when a vaccine was given according to a
 * card somebody was handed years ago. All three accept `2019`, `2019-03` or `2019-03-14`, and all
 * three must read back saying only as much as they know — padding a bare year out to the first of
 * January invents a fact, and it is the kind of invented fact that later reads as a real anniversary.
 *
 * It lives in its own file because that rule is now used by more than one caller, and a parsing rule
 * implemented twice is a parsing rule that disagrees with itself the first time either copy is
 * edited.
 *
 * Note that this rounds a partial date to the **start** of its period, which is the opposite of
 * [Cabinet.parseExpiry] and right for the same reason: an expiry is a deadline and so runs to the end
 * of its month, while everything here is a beginning. Both round in the direction that cannot
 * overstate what was written down.
 */

/** How much of a date was actually recorded. */
enum class DatePrecision { DAY, MONTH, YEAR }

/** A parsed date: what it resolves to, and how much of it somebody actually knew. */
data class PartialDate(val date: LocalDate, val precision: DatePrecision)

object PartialDates {

    /** Anything unparseable is null rather than a guess. The row keeps its text; the screen says so. */
    fun parse(text: String?): PartialDate? {
        val value = text?.trim()?.ifBlank { null } ?: return null
        return runCatching {
            when {
                value.matches(YEAR) -> PartialDate(LocalDate.of(value.toInt(), 1, 1), DatePrecision.YEAR)

                value.matches(YEAR_MONTH) -> {
                    val (y, m) = value.split('-').map { it.toInt() }
                    PartialDate(LocalDate.of(y, m, 1), DatePrecision.MONTH)
                }

                else -> PartialDate(LocalDate.parse(value), DatePrecision.DAY)
            }
        }.getOrNull()
    }

    /** "14 March 2019", "March 2019" or "2019" — never more than was recorded. */
    fun format(partial: PartialDate?): String? = when (partial?.precision) {
        null -> null
        DatePrecision.YEAR -> "${partial.date.year}"
        DatePrecision.MONTH -> "${monthName(partial.date.monthValue)} ${partial.date.year}"
        DatePrecision.DAY ->
            "${partial.date.dayOfMonth} ${monthName(partial.date.monthValue)} ${partial.date.year}"
    }

    /** The same, straight from the stored text. */
    fun format(text: String?): String? = format(parse(text))

    fun monthName(month: Int): String = MONTHS.getOrElse(month - 1) { "" }

    private val YEAR = Regex("\\d{4}")
    private val YEAR_MONTH = Regex("\\d{4}-\\d{2}")

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
}
