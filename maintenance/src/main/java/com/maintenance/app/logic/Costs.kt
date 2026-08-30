package com.maintenance.app.logic

import kotlin.math.roundToLong

/** One thing that was done to an asset and what it cost. */
data class ServiceEntry(
    val id: String,
    val assetId: String,
    val performedAt: Long,
    val costCents: Long,
    val meterValue: Long? = null
)

/** What an asset cost over a window, and how that reads per year and per mile. */
data class CostSummary(
    val totalCents: Long,
    val entries: Int,
    val firstAt: Long?,
    val lastAt: Long?,
    /** Standing costs — premiums — for the same period, annualised then pro-rated. */
    val coverageCents: Long = 0L
) {
    val allInCents: Long get() = totalCents + coverageCents
    val isEmpty: Boolean get() = entries == 0 && coverageCents == 0L
}

/**
 * What owning a thing has actually cost.
 *
 * The one rule this file keeps is that **it never annualises a window shorter than a year up to a
 * year**. Two oil changes in three months does not mean "$1,600 a year", and an app that says so is
 * an app whose numbers get quoted back at people. So [perYear] returns null until there is a year
 * of records to divide, and the screens say "not enough history yet" — which is true, and stops
 * being true on its own.
 */
object Costs {

    private const val YEAR_MILLIS = 365L * Upkeep.DAY_MILLIS

    /** Total up [entries], optionally only those on or after [since]. */
    fun summary(
        entries: List<ServiceEntry>,
        since: Long? = null,
        coverages: List<Coverage> = emptyList(),
        now: Long? = null
    ): CostSummary {
        val window = if (since == null) entries else entries.filter { it.performedAt >= since }
        val coverageCents = if (coverages.isEmpty()) {
            0L
        } else {
            val annual = Coverages.annualCents(coverages)
            val span = if (since != null && now != null) (now - since).coerceAtLeast(0L) else YEAR_MILLIS
            (annual * (span.toDouble() / YEAR_MILLIS)).roundToLong()
        }
        return CostSummary(
            totalCents = window.sumOf { it.costCents },
            entries = window.size,
            firstAt = window.minOfOrNull { it.performedAt },
            lastAt = window.maxOfOrNull { it.performedAt },
            coverageCents = coverageCents
        )
    }

    /**
     * Spend per year, or null when the history is too short to divide honestly (see the file note).
     * The span is measured from the first record to [now], not to the last one: a car serviced once
     * two years ago has been cheap since, and measuring to the last record would hide that.
     */
    fun perYear(entries: List<ServiceEntry>, now: Long): Long? {
        if (entries.isEmpty()) return null
        val first = entries.minOf { it.performedAt }
        val span = now - first
        if (span < YEAR_MILLIS) return null
        val total = entries.sumOf { it.costCents }
        return (total / (span.toDouble() / YEAR_MILLIS)).roundToLong()
    }

    /**
     * Cents per mile (or per hour): spend over the window the readings cover, divided by the
     * distance covered in it. Null when the meter cannot say how far the thing actually went.
     */
    fun centsPerMeterUnit(entries: List<ServiceEntry>, readings: List<MeterReading>): Double? {
        if (entries.isEmpty() || readings.size < 2) return null
        val from = readings.minOf { it.readAt }
        val to = readings.maxOf { it.readAt }
        val travelled = Meter.travelled(readings, from, to) ?: return null
        if (travelled <= 0L) return null
        val spend = entries.filter { it.performedAt in from..to }.sumOf { it.costCents }
        if (spend == 0L) return null
        return spend.toDouble() / travelled
    }

    /** The most expensive things done, for the "where did it all go" list. */
    fun largest(entries: List<ServiceEntry>, count: Int = 3): List<ServiceEntry> =
        entries.sortedByDescending { it.costCents }.take(count)
}
