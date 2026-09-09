package com.finance.app.logic

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * What came in, what went out, and where it went — measured, never predicted.
 *
 * Everything in this file is arithmetic over transactions that have already happened. That is a
 * deliberate limit: the app has one place where it guesses about the future ([Forecast]) and it is
 * clearly labelled as such, so that every figure on the summary screens can be quoted without a
 * caveat.
 *
 * Three exclusions apply to every roll-up here, and each is load bearing:
 *
 * - **Pending transactions.** Their amounts change. See [Transaction.pending].
 * - **Transfers.** Moving $500 from checking to savings is not $500 of spending, and counting it
 *   makes a household that saves diligently look like a household that spends recklessly. Worse, if
 *   both accounts are connected the same $500 appears twice — once out, once in — and the month
 *   nets to zero while both totals are inflated.
 * - **Card payments.** The single most common way a personal finance app double-counts. You bought
 *   $600 of groceries on a card; that is $600 of spending. Paying the card $600 from checking is not
 *   another $600 — it is the same money arriving where it was always going. Card payments are
 *   [Transaction.transfer] when both accounts are connected, and [Category.DEBT] catches the rest.
 */
object CashFlow {

    /**
     * A period's totals.
     *
     * [inCents] and [outCents] are both **positive magnitudes**; [netCents] is the difference and is
     * the only one that can be negative — which, for a month with an annual premium in it, is normal
     * rather than a crisis, and is why [Summary] is always shown beside more than one month.
     */
    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val inCents: Long,
        val outCents: Long,
        /** Spending by category, largest first, transfers and card payments already removed. */
        val byCategory: List<CategoryTotal>
    ) {
        val netCents: Long get() = inCents - outCents

        /** Days in the period, inclusive of both ends — the divisor for a daily rate. */
        val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

        /** Average spend per day. The figure a runway is built out of. */
        val dailyBurnCents: Long get() = if (days <= 0L) 0L else outCents / days
    }

    /** One category's share of a period's spending. */
    data class CategoryTotal(val category: Category, val amountCents: Long, val count: Int) {
        /** This category's share of [total], 0..1. Zero when there was no spending at all. */
        fun shareOf(total: Long): Double = if (total <= 0L) 0.0 else amountCents.toDouble() / total
    }

    /** Roll [transactions] up over the inclusive range [from]..[to]. */
    fun summarise(transactions: List<Transaction>, from: LocalDate, to: LocalDate): Summary {
        val counted = transactions.asSequence()
            .filter { !it.pending }
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            .filter { !it.transfer && it.category != Category.TRANSFER }
            .toList()

        val spend = counted.filter { it.outflow && it.category != Category.DEBT }
        val income = counted.filter { it.inflow && it.category != Category.DEBT }

        val byCategory = spend
            .groupBy { it.category }
            .map { (category, rows) ->
                CategoryTotal(category, rows.sumOf { -it.amountCents }, rows.size)
            }
            .sortedByDescending { it.amountCents }

        return Summary(
            from = from,
            to = to,
            inCents = income.sumOf { it.amountCents },
            // Card payments were excluded above so the groceries aren't counted twice; the payment
            // itself is still visible on the account it left, just not in this total.
            outCents = spend.sumOf { -it.amountCents },
            byCategory = byCategory
        )
    }

    /** The last [months] calendar months ending with the one containing [today], oldest first. */
    fun byMonth(transactions: List<Transaction>, today: LocalDate, months: Int = 6): List<Summary> {
        require(months > 0) { "a run of months has to have at least one month in it" }
        val end = YearMonth.from(today)
        return (months - 1 downTo 0).map { back ->
            val month = end.minusMonths(back.toLong())
            summarise(transactions, month.atDay(1), month.atEndOfMonth())
        }
    }

    /**
     * How long the cash on hand lasts at the recent rate of spending, in days.
     *
     * Null when there is no spending to measure — a household that spent nothing has an infinite
     * runway, and rendering that as a number is worse than rendering it as "—".
     *
     * This deliberately ignores income. A runway that assumes the next paycheque arrives is not a
     * runway; it is a budget. The number this produces answers "if everything stopped today", which
     * is the only question the word is worth using for.
     */
    fun runwayDays(cashCents: Long, recent: Summary): Long? {
        if (cashCents <= 0L) return 0L
        val burn = recent.dailyBurnCents
        if (burn <= 0L) return null
        return cashCents / burn
    }

    /**
     * The change in a category between two periods, as a fraction — `+0.18` is eighteen percent more.
     *
     * Null when the earlier period had nothing in that category, because "up from zero" is not a
     * percentage and showing it as one produces the infinite-growth figure every dashboard has been
     * embarrassed by at least once.
     */
    fun change(earlier: Long, later: Long): Double? {
        if (earlier <= 0L) return null
        return (later - earlier).toDouble() / earlier.toDouble()
    }

    /**
     * A category's typical monthly spend across [summaries], as a median rather than a mean.
     *
     * Median because one month with a new roof in it should not become "your typical housing spend".
     */
    fun typicalMonthly(summaries: List<Summary>, category: Category): Long {
        if (summaries.isEmpty()) return 0L
        val amounts = summaries.map { summary ->
            summary.byCategory.firstOrNull { it.category == category }?.amountCents ?: 0L
        }
        return Recurring.median(amounts)
    }

    /** Scale a figure without letting the rounding out of this file. */
    internal fun scale(amountCents: Long, factor: Double): Long = (amountCents * factor).roundToLong()
}
