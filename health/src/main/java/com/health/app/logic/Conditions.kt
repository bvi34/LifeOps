package com.health.app.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The long-running things — asthma, eczema, coeliac, migraine, a heart murmur somebody is watching.
 *
 * ### Why this is not an episode
 *
 * Health already has a container for illness, and it is deliberately the wrong shape for this one.
 * An [EpisodeSummary] is built around a bout with a start and an end: it counts days from Day 1,
 * reports a fever run, escalates when one reaches its fourth day, and the repository enforces one
 * open episode per person so "how long has this been going on" has a single answer. Every one of
 * those is right for a cold and wrong for asthma. An episode left open for nine years would report a
 * nine-year illness in its four-thousandth day, block every future episode that person has, and
 * quietly corrupt the one number the illness screen exists to give.
 *
 * So a condition is its own row with its own vocabulary. It has an **onset** rather than a start, a
 * [ConditionStatus] rather than an end, and it is expected to outlive every episode filed under it.
 *
 * ### Onset is recorded at the precision it is known
 *
 * Nobody remembers the day their child's eczema started; plenty of people remember the year, and
 * some remember the month of the appointment where it was named. So an onset is stored as ISO text
 * at whatever precision was given — `2019`, `2019-03`, or `2019-03-14` — and read back saying only
 * as much as it knows. Padding a bare year out to the first of January would invent a fact, and it
 * is the kind of invented fact that later reads as a real anniversary.
 */

/** Where a condition stands. Ordered so the ones still being managed sort to the top of a list. */
enum class ConditionStatus(val key: String, val label: String) {
    ACTIVE("active", "Active"),

    /** Still theirs, not currently doing anything. The honest middle that a yes/no flag loses. */
    REMISSION("remission", "In remission"),

    RESOLVED("resolved", "Resolved");

    val isCurrent: Boolean get() = this == ACTIVE || this == REMISSION

    companion object {
        fun fromKey(key: String?): ConditionStatus = entries.firstOrNull { it.key == key } ?: ACTIVE
    }
}

object Conditions {

    /**
     * Read an onset written at any of the three precisions people actually know.
     *
     * Delegates to [PartialDates], which is where that rule lives now that vaccination dates need it
     * too — see the note there for why a partial date rounds to the start of its period.
     */
    fun parseOnset(text: String?): PartialDate? = PartialDates.parse(text)

    /**
     * "Since March 2019 · 7 years". The two halves answer different questions — when it started, and
     * how long that has been — and a household asks both.
     *
     * The "since" half never says more than [parseOnset] read: a bare year reads "Since 2019", not
     * "Since 1 January 2019".
     */
    fun describeOnset(onsetText: String?, today: LocalDate = LocalDate.now()): String? {
        val onset = parseOnset(onsetText) ?: return null
        if (onset.date.isAfter(today)) return null

        val since = "Since ${PartialDates.format(onset)}"
        val elapsed = describeElapsed(onset.date, today)
        return if (elapsed == null) since else "$since · $elapsed"
    }

    /**
     * How long ago, in the unit a person would use out loud — years once there is more than one,
     * months below that, and nothing at all for something that started this month.
     *
     * "0 years" is the reading of a recent onset that would look like a bug, and "84 months" is the
     * reading of an old one that nobody says. Both are avoided by picking the unit rather than
     * fixing one.
     */
    fun describeElapsed(onset: LocalDate, today: LocalDate): String? {
        if (onset.isAfter(today)) return null
        val months = ChronoUnit.MONTHS.between(onset, today)
        return when {
            months >= 24 -> "${months / 12} years"
            months >= 12 -> "1 year"
            months >= 1 -> "$months ${if (months == 1L) "month" else "months"}"
            else -> null
        }
    }

    /**
     * The order a person's conditions are read in: what is still going first, then what is being
     * watched, then what is over — and within each, the longest-standing first.
     *
     * A resolved condition stays on the record and stays visible. It is not clutter: "she had this
     * as a toddler" is the answer to a question a doctor asks, and deleting it to tidy the list is
     * how a record stops being one.
     */
    fun <T> sort(
        items: List<T>,
        status: (T) -> ConditionStatus,
        onset: (T) -> String?,
        name: (T) -> String
    ): List<T> = items.sortedWith(
        compareBy<T> { status(it).ordinal }
            .thenBy { parseOnset(onset(it))?.date ?: LocalDate.MAX }
            .thenBy { name(it).lowercase() }
    )
}
