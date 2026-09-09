package com.finance.app.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The one screen in this app that talks about the future, and the rules that keep it honest.
 *
 * The question it answers is narrow and worth stating exactly: **given the cash on hand and the
 * bills already known about, what is the lowest the balance gets between now and the horizon, and
 * on what day?** That day — [Projection.trough] — is the number the Picture screen leads with,
 * because it is the one that changes what somebody does this afternoon.
 *
 * ## What is projected, and what is refused
 *
 * - **Bills are projected.** They have dates and amounts, from a statement or from a pattern with
 *   three occurrences behind it.
 * - **Everyday spending is projected**, as a flat daily rate from the recent past ([CashFlow]).
 *   Not because Tuesdays and Saturdays cost the same — they plainly don't — but because a
 *   day-of-week model would be false precision on a number whose job is to be roughly right for a
 *   fortnight.
 * - **Income is refused.** This is the decision that makes the forecast worth reading. Payroll is
 *   regular and easily detected, and projecting it would produce a much prettier line — one that
 *   dips towards the 1st and recovers on the 15th, and never shows a problem. But a projected
 *   paycheque is a promise about somebody's employer, and the cost of being wrong lands on the
 *   household rather than on the app. So the line only ever falls, and the trough it finds is a
 *   floor: **the real balance on that day will be this or better, never worse.** A forecast you can
 *   say that sentence about is worth having; one that averages out to roughly right is not.
 *
 * That last point is why nothing here is called a "prediction of your balance". It is a floor, and
 * [Projection.headline] words it as one.
 */
object Forecast {

    /** How far out to project by default. Six weeks covers a monthly cycle and a bit of the next. */
    const val DEFAULT_HORIZON_DAYS = 45L

    /** One day on the projected line. */
    data class Day(
        val date: LocalDate,
        /** The floor for this day's cash, in cents. Can and does go negative. */
        val balanceCents: Long,
        /** The bills falling due on this day, for annotating the point. */
        val bills: List<Bills.Bill>
    )

    /** The projection, and the two facts worth taking from it. */
    data class Projection(
        val days: List<Day>,
        /** The lowest point on the line. Null only when the horizon was zero days. */
        val trough: Day?,
        /** The first day the line goes below zero, if it does. */
        val shortfall: Day?,
        /** What the bills between now and the horizon add up to. */
        val committedCents: Long,
        /** The daily rate of ordinary spending the line was built with. */
        val dailyBurnCents: Long
    ) {
        val overdrawn: Boolean get() = shortfall != null

        /**
         * One sentence, worded as the floor it is.
         *
         * The wording matters as much as the arithmetic here — "you'll have $412 on the 28th" is a
         * claim the app cannot support, and "at worst $412 on the 28th" is exactly the claim it can.
         */
        fun headline(today: LocalDate, symbol: String = "$"): String {
            val low = trough ?: return "Nothing scheduled to project against."
            val money = com.operations.suitekit.SuiteMoney.format(low.balanceCents, symbol, cents = false)
            val days = ChronoUnit.DAYS.between(today, low.date)
            val whenever = when {
                days <= 0L -> "today"
                days == 1L -> "tomorrow"
                else -> "in $days days"
            }
            return if (low.balanceCents < 0L) {
                "Short by ${com.operations.suitekit.SuiteMoney.format(-low.balanceCents, symbol, cents = false)} $whenever, before anything else comes in."
            } else {
                "At worst $money $whenever, before anything else comes in."
            }
        }
    }

    /**
     * Project the cash line forward from [startingCashCents].
     *
     * [dailyBurnCents] is a positive magnitude — the ordinary daily spend, from
     * [CashFlow.Summary.dailyBurnCents]. Pass zero to see the bills alone, which is what the screen
     * offers as "bills only" for somebody who wants the schedule without the estimate mixed in.
     *
     * Bills already [Bills.Bill.paid] are skipped: the money has gone, and the balance passed in
     * already reflects it. Counting them would take it out twice, which is the mistake that makes a
     * forecast look alarming a week after payday every month.
     */
    fun project(
        startingCashCents: Long,
        bills: List<Bills.Bill>,
        dailyBurnCents: Long,
        today: LocalDate,
        horizonDays: Long = DEFAULT_HORIZON_DAYS
    ): Projection {
        if (horizonDays <= 0L) {
            return Projection(emptyList(), null, null, 0L, dailyBurnCents)
        }

        val horizon = today.plusDays(horizonDays)
        val due = bills.asSequence()
            .filter { !it.paid }
            // An overdue bill is still money that has to go out, so it lands on today rather than
            // being dropped for having a date in the past. Dropping it would let the line show cash
            // the household has already committed.
            .filter { !it.dueDate.isAfter(horizon) }
            .groupBy { if (it.dueDate.isBefore(today)) today else it.dueDate }

        val line = mutableListOf<Day>()
        var balance = startingCashCents
        var date = today
        while (!date.isAfter(horizon)) {
            val onThisDay = due[date].orEmpty()
            balance -= onThisDay.sumOf { it.amountCents }
            // The first day's ordinary spending has mostly already happened by the time anybody
            // looks at this screen, so today is charged bills only. Every later day takes the rate.
            if (date != today) balance -= dailyBurnCents
            line += Day(date = date, balanceCents = balance, bills = onThisDay)
            date = date.plusDays(1)
        }

        return Projection(
            days = line,
            // minByOrNull keeps the *first* of equal minima, which is the right one to report: the
            // day the trouble starts, not the last day it is still going on.
            trough = line.minByOrNull { it.balanceCents },
            shortfall = line.firstOrNull { it.balanceCents < 0L },
            committedCents = due.values.flatten().sumOf { it.amountCents },
            dailyBurnCents = dailyBurnCents
        )
    }

    /**
     * How much would have to arrive, and by when, to keep the line above [floorCents].
     *
     * The practical version of the trough: "you need $340 in by Friday" is actionable in a way that
     * "your projected minimum is -$340" is not. Null when the line never goes below the floor.
     *
     * [floorCents] defaults to zero but is meant to be set to whatever the household treats as a
     * genuine floor — a buffer they don't go under — which is a more useful alarm than the overdraft.
     */
    fun shortfallToCover(projection: Projection, floorCents: Long = 0L): Pair<Long, LocalDate>? {
        val breach = projection.days.firstOrNull { it.balanceCents < floorCents } ?: return null
        val worst = projection.days.minByOrNull { it.balanceCents } ?: return null
        return (floorCents - worst.balanceCents) to breach.date
    }
}
