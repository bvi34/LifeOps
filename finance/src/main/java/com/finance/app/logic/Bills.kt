package com.finance.app.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * What is due, when, and how much of that the app actually knows.
 *
 * A bill in this app comes from one of three places, and they are **not** equally trustworthy. The
 * difference is carried on every bill as its [Bill.source] and is shown rather than hidden, because
 * the app's credibility rests on not presenting a guess in the same typeface as a statement:
 *
 * - **[Source.STATEMENT]** — the institution said so. A credit card's `next_payment_due_date` and
 *   minimum payment, a mortgage servicer's next payment. This is a fact with a date on it, and it is
 *   the reason connecting a card is worth doing at all.
 * - **[Source.PREDICTED]** — [Recurring] found a pattern and stepped it forward. This is a good
 *   guess about a real obligation whose exact date and amount are the app's arithmetic, not the
 *   biller's.
 * - **[Source.MANUAL]** — somebody typed it in. The rent that is paid by standing order from an
 *   account nobody connected, the quarterly tax estimate. As trustworthy as the person who typed it,
 *   which is usually the most trustworthy thing here.
 *
 * ## Paid, and how it is known
 *
 * A bill is not marked paid by anybody ticking it. It is marked paid when **a charge that looks like
 * it turns up** — [settle] matches an outflow to the payee within a window around the due date — or
 * when the LifeOps task it published is ticked. Both are corrections after the fact rather than a
 * promise, which is why the window is generous in both directions: a bill paid four days early is a
 * bill paid, and a bill still unmatched a week after its date is one worth going and looking at.
 */
object Bills {

    /** How much slack there is around a due date when matching a payment to it. */
    const val SETTLE_WINDOW_DAYS = 7L

    /** How close a payment's amount has to be to the bill's for it to count as that bill. */
    const val SETTLE_AMOUNT_TOLERANCE = 0.25

    /** Inside this many days, a bill has stopped being "later" and started being "this week". */
    const val SOON_DAYS = 7L

    /** Where a bill's claim comes from, in descending order of how much it should be believed. */
    enum class Source(val key: String, val label: String) {
        STATEMENT("statement", "From your statement"),
        MANUAL("manual", "You entered this"),
        PREDICTED("predicted", "Predicted from your history");

        companion object {
            fun fromKey(key: String?): Source =
                entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: PREDICTED
        }
    }

    /**
     * One obligation with a date on it.
     *
     * [amountCents] is a **positive magnitude**, like [Recurring.Series.typicalAmountCents] and for
     * the same reason: a bill is a thing you are told about, not a ledger line.
     *
     * [minimumCents] exists only for revolving credit and is the one place this app takes a position
     * on personal finance: for a card it publishes the **statement balance** as the amount and keeps
     * the minimum beside it, because paying the minimum is how a balance becomes permanent and an
     * app that leads with the minimum is quietly recommending that.
     */
    data class Bill(
        val id: String,
        val accountId: String,
        val payee: String,
        val dueDate: LocalDate,
        val amountCents: Long,
        val minimumCents: Long? = null,
        val source: Source,
        val category: Category = Category.OTHER,
        /** [Merchants.key] of the payee, for matching payments to it. Null for a card statement. */
        val merchantKey: String? = null,
        /** When a payment was matched to it. Null while it is still outstanding. */
        val paidOn: LocalDate? = null,
        val autopay: Boolean = false,
        /** Whether this bill should put itself on the LifeOps week. Autopaid ones default to not. */
        val publishToWeek: Boolean = true,
        /**
         * How often a **manual** bill comes back, in months. Null is a one-off.
         *
         * Only manual bills carry this, and the asymmetry is the point. A statement bill's next
         * occurrence comes from the institution, which knows; a predicted one's comes from
         * [Recurring], which has three occurrences of evidence. A manual bill has neither — nobody
         * is going to tell us the rent is due again — so the person who typed it says how often, and
         * that answer is the only one available.
         */
        val recurrenceMonths: Int? = null
    ) {
        val paid: Boolean get() = paidOn != null

        fun daysUntil(today: LocalDate): Long = ChronoUnit.DAYS.between(today, dueDate)

        fun status(today: LocalDate): Status = when {
            paid -> Status.PAID
            dueDate.isBefore(today) -> Status.OVERDUE
            daysUntil(today) <= SOON_DAYS -> Status.DUE_SOON
            else -> Status.UPCOMING
        }
    }

    /** How a bill reads today. */
    enum class Status { OVERDUE, DUE_SOON, UPCOMING, PAID }

    /**
     * Turn the recurring series into predicted bills, skipping any a real statement already covers.
     *
     * The skip is the point. Connect a credit card and its statement gives a real due date; the same
     * card's payment also appears in checking every month as a textbook recurring series. Showing
     * both is showing the same obligation twice, and the statement is the one that is true — so a
     * predicted bill is dropped when a statement bill for the same payee falls within a fortnight of
     * it.
     */
    fun predict(
        series: List<Recurring.Series>,
        today: LocalDate,
        horizonDays: Long,
        existing: List<Bill> = emptyList()
    ): List<Bill> {
        val horizon = today.plusDays(horizonDays)
        val statements = existing.filter { it.source == Source.STATEMENT }
        return series.mapNotNull { candidate ->
            val due = candidate.nextAfter(today.minusDays(1))
            if (due.isAfter(horizon)) return@mapNotNull null
            val duplicated = statements.any { bill -> coversSameObligation(bill, candidate, due) }
            if (duplicated) return@mapNotNull null
            Bill(
                id = "predicted:${candidate.accountId}:${candidate.merchantKey}:$due",
                accountId = candidate.accountId,
                payee = candidate.label,
                dueDate = due,
                amountCents = candidate.typicalAmountCents,
                source = Source.PREDICTED,
                category = candidate.category,
                merchantKey = candidate.merchantKey
            )
        }
    }

    /**
     * The id a manual bill is filed under.
     *
     * Derived rather than random, exactly as the provider-backed ids are, and for a reason specific
     * to this source: a manual bill has to be able to *repeat*, and repeating means minting next
     * month's occurrence without minting a second copy of one that already exists. Deriving the id
     * from the account, the payee and the date makes "the same occurrence" the same row by
     * construction, so [rollForward] can be run as often as anything else in this module without
     * accumulating duplicates.
     */
    fun manualId(accountId: String, merchantKey: String, due: LocalDate): String =
        "manual:$accountId:$merchantKey:$due"

    /**
     * Mint the occurrences a repeating manual bill owes but does not yet have.
     *
     * Only manual bills, and only ones the person said repeat. Statement bills get their next date
     * from the institution and predicted ones from [Recurring]; a manual bill has neither, which is
     * why it is the one source that needs this at all.
     *
     * The rule is deliberately conservative: **step from the latest occurrence that already exists**,
     * never from today. A household that stops opening the app for three months should come back to
     * a rent bill for each of those months — visibly unpaid, which is the truth — rather than to one
     * bill dated today that quietly pretends the gap did not happen. What stops that running away is
     * the horizon: the series only ever advances to it, and [MAX_ROLL_STEPS] bounds a corrupt row.
     *
     * Returns only the **new** occurrences, so the caller can insert them without touching anything
     * it already holds.
     */
    fun rollForward(bills: List<Bill>, today: LocalDate, horizonDays: Long): List<Bill> {
        val horizon = today.plusDays(horizonDays)
        return bills.asSequence()
            .filter { it.source == Source.MANUAL && (it.recurrenceMonths ?: 0) > 0 }
            // One series per payee per account. Grouping by the same two fields the id is derived
            // from is what makes the generated ids collide with anything already present.
            .groupBy { it.accountId to it.merchantKey.orEmpty() }
            .flatMap { (key, occurrences) ->
                val (accountId, merchantKey) = key
                val latest = occurrences.maxBy { it.dueDate }
                val months = latest.recurrenceMonths?.toLong() ?: return@flatMap emptyList()
                val taken = occurrences.mapTo(mutableSetOf()) { it.dueDate }

                val minted = mutableListOf<Bill>()
                var due = latest.dueDate
                var steps = 0
                while (steps < MAX_ROLL_STEPS) {
                    due = due.plusMonths(months)
                    steps++
                    if (due.isAfter(horizon)) break
                    if (!taken.add(due)) continue
                    minted += latest.copy(
                        id = manualId(accountId, merchantKey, due),
                        dueDate = due,
                        // A fresh occurrence owes nothing yet and holds no claim on anybody's week
                        // until the round decides it should.
                        paidOn = null
                    )
                }
                minted
            }
    }

    /**
     * Whether a statement bill and a predicted one are the same obligation seen twice.
     *
     * Close in time, and then one of two tests depending on what the statement actually knows:
     *
     * - **Both have a payee key** → they have to match. A predicted bill's key is derived from a bank
     *   string, so when a statement has one too, comparing them is exact and cheap.
     * - **The statement has none** → compare the amounts instead. This is the case that matters,
     *   because it is the normal one: a Plaid liability names the *account* ("USAA Rewards Visa") and
     *   the prediction is keyed on whatever the checking account called the transfer ("USAA CARD
     *   PAYMENT", "ACH PMT 4821"). There is no reliable string relationship between those two, which
     *   is the same reason [matches] settles a card statement on amount alone.
     *
     * Comparing keys alone — which this did at first — meant the de-duplication never fired for a
     * real Plaid statement, and every connected card showed its bill twice: once from the issuer,
     * once predicted from the payment leaving checking.
     */
    private fun coversSameObligation(
        statement: Bill,
        candidate: Recurring.Series,
        due: LocalDate
    ): Boolean {
        if (abs(ChronoUnit.DAYS.between(statement.dueDate, due)) > DUPLICATE_WINDOW_DAYS) return false
        val statementKey = statement.merchantKey
        if (statementKey != null) return statementKey == candidate.merchantKey
        val gap = abs(candidate.typicalAmountCents - statement.amountCents).toDouble()
        return gap <= statement.amountCents * SETTLE_AMOUNT_TOLERANCE
    }

    /**
     * Mark the bills in [bills] that a transaction in [payments] looks like it settled.
     *
     * Matching is by payee key, then by date window, then by amount — and a payment is consumed by
     * at most one bill, so two identical monthly charges in the same window do not both settle the
     * same bill and leave the other looking unpaid. Bills are matched nearest-date-first for the same
     * reason: when a payee has two bills in flight, the payment belongs to the older one.
     *
     * A card statement bill matches on **amount alone within the window**, because the payee key for
     * "pay off the Visa" is whatever the checking account called the transfer and there is no
     * reliable string relationship between the two.
     */
    fun settle(bills: List<Bill>, payments: List<Transaction>): List<Bill> {
        val available = payments
            .filter { it.outflow && !it.pending }
            .sortedBy { it.date }
            .toMutableList()

        return bills.sortedBy { it.dueDate }.map { bill ->
            if (bill.paid) return@map bill
            val match = available.firstOrNull { payment -> matches(bill, payment) } ?: return@map bill
            available.remove(match)
            bill.copy(paidOn = match.date)
        }.sortedWith(compareBy({ it.dueDate }, { it.payee }))
    }

    private fun matches(bill: Bill, payment: Transaction): Boolean {
        val gap = abs(ChronoUnit.DAYS.between(bill.dueDate, payment.date))
        if (gap > SETTLE_WINDOW_DAYS) return false
        val amount = abs(payment.amountCents)
        // A card payment for more than the statement balance is still that card's payment; paying
        // less than the balance but at least the minimum is a payment too. Only the amount being
        // wildly off — or a token amount against a large balance — fails the check.
        val nearAmount = abs(amount - bill.amountCents).toDouble() <= bill.amountCents * SETTLE_AMOUNT_TOLERANCE
        val atLeastMinimum = bill.minimumCents?.let { amount >= it } ?: false
        if (!nearAmount && !atLeastMinimum) return false
        val key = bill.merchantKey ?: return true
        val paid = Merchants.key(payment.label())
        if (bill.source != Source.MANUAL) return key == paid

        // A manual bill is the one case where the key was typed by a person rather than derived
        // from a bank string, so exact matching is the wrong test: somebody writes "Landlord" and
        // the bank says "LANDLORD SEPT AUTOPAY". Without this, a typed bill would essentially never
        // settle itself and the household would be ticking rent off by hand forever.
        //
        // The relaxation is one-sided prefix matching with a floor on length, because a short prefix
        // is a bad match rather than a generous one: "LAND" would otherwise settle a landscaping
        // invoice. Four characters is enough to stop that and short enough for a real payee name.
        if (key.length < MIN_MANUAL_KEY || paid.length < MIN_MANUAL_KEY) return key == paid
        return key.startsWith(paid) || paid.startsWith(key)
    }

    /**
     * The bills falling due between [today] and [horizonDays] out, in the order to show them.
     *
     * Overdue first — an overdue bill is the only thing on this screen that is already a problem —
     * then by date. Paid ones are kept rather than hidden, because "the insurance went out on the
     * 3rd" is exactly as useful as "the insurance goes out on the 3rd", and a list that empties
     * itself as the month progresses looks like the app forgot.
     */
    fun upcoming(bills: List<Bill>, today: LocalDate, horizonDays: Long = 45L): List<Bill> {
        val horizon = today.plusDays(horizonDays)
        return bills
            .filter { !it.dueDate.isAfter(horizon) }
            .filter { it.paid || !it.dueDate.isBefore(today.minusDays(OVERDUE_MEMORY_DAYS)) }
            .sortedWith(
                compareBy(
                    { it.status(today) != Status.OVERDUE },
                    { it.dueDate },
                    { it.payee }
                )
            )
    }

    /** What the household owes before [through], counting only what is not already settled. */
    fun committedCents(bills: List<Bill>, today: LocalDate, through: LocalDate): Long =
        bills.asSequence()
            .filter { !it.paid && !it.dueDate.isBefore(today) && !it.dueDate.isAfter(through) }
            .sumOf { it.amountCents }

    /**
     * How long an unpaid bill keeps being shown after its date passes.
     *
     * Sixty days, because a bill that never got matched to a payment is either a bill somebody
     * genuinely missed — which must not quietly disappear — or a prediction that was wrong, which is
     * worth seeing so the prediction can be deleted. What must not happen is it aging out silently.
     */
    private const val OVERDUE_MEMORY_DAYS = 60L

    /**
     * How much of a typed payee has to match a bank string before it counts as the same payee.
     *
     * Only manual bills are matched this loosely; see [matches].
     */
    private const val MIN_MANUAL_KEY = 4

    /** How close a prediction has to be to a statement bill to be considered the same obligation. */
    private const val DUPLICATE_WINDOW_DAYS = 14L

    /**
     * A bound on how far one call to [rollForward] will step a series.
     *
     * The horizon already stops it in every sane case; this stops a row with a corrupt date — a
     * recurrence of zero months would otherwise never advance — from spinning.
     */
    private const val MAX_ROLL_STEPS = 240
}
