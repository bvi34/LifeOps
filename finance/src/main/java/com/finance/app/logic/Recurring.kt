package com.finance.app.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Finding the things that come round.
 *
 * This is the part of the app that has to earn its keep, because it is the difference between "here
 * are your transactions, good luck" and "the insurance goes out on the 3rd and it went up $18".
 * Nothing about it is machine learning: it is a group-by, a median, and three thresholds that were
 * chosen for stated reasons and can be argued with.
 *
 * ## What it looks for
 *
 * A charge is recurring when the same payee has taken money from the same account **at least three
 * times**, the gaps between those times are **consistent**, and the amounts are **close**. Each of
 * those three has a threshold and each threshold has a cost when it is wrong, so:
 *
 * - **Three occurrences, not two.** Two charges a month apart are a coincidence often enough to
 *   matter — a shop you happened to visit twice — and the cost of a false positive here is not a
 *   cosmetic one: it puts a task on somebody's LifeOps week for a bill that does not exist. Three is
 *   where the pattern starts being a pattern.
 * - **Gaps within [DAY_TOLERANCE] of the cadence.** Monthly bills land on the same date, which means
 *   gaps of 28 to 31 days, and weekend shifts move them two more. Four days' slack covers that
 *   without letting a 45-day gap pass as monthly.
 * - **Amounts within [AMOUNT_TOLERANCE] of the median.** A utility bill is never the same twice —
 *   that is the point of a utility bill — but a subscription is, and both should be found. Twenty
 *   percent catches a heating bill through a mild winter and refuses to group a $4 coffee with a $60
 *   dinner at the same café.
 *
 * ## What it deliberately does not do
 *
 * It does not detect *income*. Payroll is regular and would be found by exactly this code, but a
 * predicted paycheque in the forecast is a promise the app cannot keep — hours change, bonuses land,
 * jobs end — and the forecast below is much more useful being honestly pessimistic about money
 * coming in than optimistically wrong. Income is measured from what actually arrived (see
 * [CashFlow]), never projected from a pattern.
 */
object Recurring {

    /** How far a gap may sit from a cadence's nominal length and still count as that cadence. */
    const val DAY_TOLERANCE = 4L

    /** How far an amount may sit from the series median and still belong to it. */
    const val AMOUNT_TOLERANCE = 0.20

    /** The floor for calling something a series at all. See the class note. */
    const val MIN_OCCURRENCES = 3

    /**
     * How often a thing comes round.
     *
     * [days] is the nominal gap and is what the tolerance is measured against; [advance] is how the
     * next date is actually computed, and the two differ on purpose. A monthly bill is not "every 30
     * days" — it is "the 14th" — and stepping by 30 days walks a January bill to the 13th of March
     * and the 12th of May. So the *matching* uses days (because that is what the data gives you) and
     * the *prediction* uses calendar arithmetic (because that is what the biller does).
     */
    enum class Cadence(val days: Long, val label: String) {
        WEEKLY(7, "Weekly"),
        BIWEEKLY(14, "Every 2 weeks"),
        MONTHLY(30, "Monthly"),
        QUARTERLY(91, "Quarterly"),
        ANNUAL(365, "Yearly");

        /** Step [from] forward by one period, in the units the biller actually uses. */
        fun advance(from: LocalDate): LocalDate = when (this) {
            WEEKLY -> from.plusWeeks(1)
            BIWEEKLY -> from.plusWeeks(2)
            MONTHLY -> from.plusMonths(1)
            QUARTERLY -> from.plusMonths(3)
            ANNUAL -> from.plusYears(1)
        }

        companion object {
            /**
             * The cadence a gap of [gapDays] belongs to, or null when it matches none.
             *
             * The *closest* match rather than the first one that fits, which matters at the seams:
             * a ten-day gap is within four days of both weekly and fortnightly, and taking whichever
             * happened to be declared first would make the answer depend on the order of an enum.
             */
            fun forGap(gapDays: Long): Cadence? =
                entries.filter { abs(gapDays - it.days) <= toleranceFor(it) }
                    .minByOrNull { abs(gapDays - it.days) }

            /**
             * Longer cadences get proportionally more slack.
             *
             * A yearly premium billed on "the first Monday of March" moves by up to six days between
             * years and is still unmistakably yearly; four days of slack on a 365-day gap would
             * reject it. Quarterly gets the same treatment for the same reason — 89 to 92 days is a
             * quarter, depending which one.
             */
            private fun toleranceFor(cadence: Cadence): Long = when (cadence) {
                ANNUAL -> 20L
                QUARTERLY -> 8L
                else -> DAY_TOLERANCE
            }
        }
    }

    /**
     * A payee that has been paid repeatedly, and what that series looks like.
     *
     * [typicalAmountCents] is a **positive magnitude** — the size of the charge — because a series is
     * a thing you are told about ("Netflix, $15.49, monthly") rather than a ledger line.
     */
    data class Series(
        /** [Merchants.key] of the payee — stable across the reference numbers in the description. */
        val merchantKey: String,
        /** The nicest human label seen for this payee, for showing. */
        val label: String,
        val accountId: String,
        val cadence: Cadence,
        val typicalAmountCents: Long,
        /** The most recent charge in the series. */
        val lastSeen: LocalDate,
        /** How many charges the series was built from. */
        val occurrences: Int,
        val category: Category,
        /**
         * How much the amounts move about, as a fraction of the typical amount.
         *
         * Zero for a subscription, a quarter for a heating bill. Carried so a screen can say "about
         * $180" rather than "$180" when the app has no business being that precise.
         */
        val variability: Double
    ) {
        /** Whether this is a fixed charge you can quote, or one that moves every time. */
        val fixed: Boolean get() = variability <= 0.02

        /** The next date this is expected, stepping from [lastSeen] until the result is after [after]. */
        fun nextAfter(after: LocalDate): LocalDate {
            var next = cadence.advance(lastSeen)
            var guard = 0
            while (!next.isAfter(after) && guard < MAX_STEPS) {
                next = cadence.advance(next)
                guard++
            }
            return next
        }
    }

    /**
     * Find every series in [transactions].
     *
     * Outflows only, non-pending only, transfers excluded — a standing transfer into savings is
     * regular and is not a bill, and putting it on somebody's week as one would be the app
     * misunderstanding what it was looking at.
     */
    fun detect(transactions: List<Transaction>): List<Series> =
        transactions.asSequence()
            .filter { it.outflow && !it.pending && !it.transfer }
            .groupBy { it.accountId to Merchants.key(it.label()) }
            .mapNotNull { (group, rows) -> seriesFrom(group.first, group.second, rows) }
            .sortedWith(compareByDescending<Series> { it.typicalAmountCents }.thenBy { it.label })
            .toList()

    /**
     * Build one series from one payee's charges on one account, or null when they aren't a series.
     *
     * **Amounts are filtered before the cadence is looked at**, and the order is the interesting
     * decision in this file. The obvious way round — establish the rhythm, then check the amounts —
     * fails on a case that is not exotic at all: a gym charged $45 monthly that also takes $180 once
     * a year for the membership renewal. That extra charge lands in the middle of a month, splitting
     * one 30-day gap into a 15 and a 16, and a cadence check run first sees an irregular payee and
     * throws away a series that is plainly monthly.
     *
     * Filtering on amount first drops the $180 as not belonging to this series, and what is left is
     * six clean monthly gaps. The cost is the mirror case — a payee whose amounts genuinely swing
     * more than [AMOUNT_TOLERANCE] loses rows before its rhythm is examined — and that is the right
     * trade, because a charge that is neither the same size nor on the same schedule is not
     * something this app should be putting on somebody's week.
     */
    private fun seriesFrom(accountId: String, merchantKey: String, rows: List<Transaction>): Series? {
        if (merchantKey.isBlank() || rows.size < MIN_OCCURRENCES) return null

        val byDate = rows.sortedBy { it.date }
        val typical = median(byDate.map { abs(it.amountCents) })
        if (typical <= 0L) return null

        val inTolerance = byDate.filter {
            abs(abs(it.amountCents) - typical).toDouble() <= typical * AMOUNT_TOLERANCE
        }
        if (inTolerance.size < MIN_OCCURRENCES) return null

        val gaps = inTolerance.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
            .filter { it > 0L }
        if (gaps.size < MIN_OCCURRENCES - 1) return null

        val cadence = Cadence.forGap(median(gaps)) ?: return null

        // The gaps have to fit too, not just their median — a payee charged on the 1st for six
        // months and then twice in one week has a monthly median and is not monthly any more. One
        // gap is allowed to miss, which is a skipped month rather than a change of rhythm.
        val consistent = gaps.count { Cadence.forGap(it) == cadence }
        if (consistent < gaps.size - 1) return null

        val kept = inTolerance.map { abs(it.amountCents) }
        val keptTypical = median(kept)
        val spread = kept.maxOf { abs(it - keptTypical) }.toDouble() / keptTypical.toDouble()

        val last = inTolerance.last()
        return Series(
            merchantKey = merchantKey,
            label = bestLabel(inTolerance),
            accountId = accountId,
            cadence = cadence,
            typicalAmountCents = keptTypical,
            lastSeen = last.date,
            occurrences = inTolerance.size,
            // The category the payee is *mostly* filed under. Providers occasionally re-categorise a
            // merchant, and one stray row should not decide what the series is.
            category = inTolerance.groupingBy { it.category }.eachCount()
                .maxByOrNull { it.value }?.key ?: Category.OTHER,
            variability = spread
        )
    }

    /**
     * The label to show for a series: the shortest one seen, preferring a provider-identified
     * merchant name.
     *
     * Shortest because the long ones are the ones carrying reference numbers and routing noise —
     * `"NETFLIX"` and `"NETFLIX.COM 8667169929 CA"` describe the same charge, and the first is the
     * one a person would write down.
     */
    private fun bestLabel(rows: List<Transaction>): String {
        val named = rows.mapNotNull { it.merchant?.takeIf { m -> m.isNotBlank() } }
        val pool = named.ifEmpty { rows.map { it.description } }
        return pool.minByOrNull { it.length } ?: pool.first()
    }

    /** The middle value, averaging the two middles for an even count. Longs in, Long out. */
    internal fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "no values to take a median of" }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToLong()
    }

    /** A series stepping forward can't loop forever on a corrupt date; twenty years is plenty. */
    private const val MAX_STEPS = 1040
}
