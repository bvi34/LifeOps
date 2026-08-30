package com.maintenance.app.logic

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What is owed on a thing, and what that means today.
 *
 * A mortgage is the reason "asset" and "maintenance" belong in one app: the house is the thing you
 * service *and* the thing you are paying off, and both facts are asked about in the same breath —
 * what is it worth, what is left on it, when is it clear. Auto loans work identically, which is why
 * this is one set of functions and not two.
 *
 * It is amortisation arithmetic, and nothing else. There is no rate table, no bank connection and
 * no attempt to be a payoff optimiser: you type what the note says — principal, rate, term, start
 * date, the payment — and this answers the questions that follow from it. Every figure is derived,
 * so a balance shown here is never a stale number somebody forgot to update; the one number that
 * *can* go stale is escrow, which is why it is stored separately and labelled as what it is.
 *
 * Rates are held in **basis points** (6.25% → 625) rather than as a double, for the same reason
 * money is held in cents: a rate typed as 6.25 and stored as 6.2499999 compounds three hundred and
 * sixty times.
 */
data class LoanTerms(
    val principalCents: Long,
    val annualRateBps: Int,
    val termMonths: Int,
    /**
     * The payment actually made each month, when it differs from the scheduled one — a rounded-up
     * payment, or a fixed extra towards principal. Null means "whatever the note works out to".
     */
    val paymentCents: Long? = null,
    /** Taxes and insurance collected with the payment. Not debt; never amortised. */
    val escrowCents: Long = 0L
) {
    /** What actually leaves the account each month, escrow included. */
    fun totalMonthlyCents(): Long = Loan.payment(this) + escrowCents
}

/** Where a loan stands on a given day. Every field is derived from [LoanTerms] and the date. */
data class LoanSnapshot(
    val paymentsMade: Int,
    val balanceCents: Long,
    val principalPaidCents: Long,
    val interestPaidCents: Long,
    val paymentsRemaining: Int?,
    val scheduledPaymentCents: Long
) {
    val isPaidOff: Boolean get() = balanceCents <= 0L

    /** How far through the debt you are, by principal rather than by payments made. */
    fun progress(principalCents: Long): Float =
        if (principalCents <= 0L) 1f else (principalPaidCents.toDouble() / principalCents).coerceIn(0.0, 1.0).toFloat()
}

object Loan {

    /**
     * The scheduled monthly payment — the standard amortisation formula, with the zero-rate case
     * handled rather than divided by.
     *
     * An interest-free family loan is a real thing people track, and `0 / 0` is not a good answer
     * to it.
     */
    fun scheduledPayment(principalCents: Long, annualRateBps: Int, termMonths: Int): Long {
        if (termMonths <= 0 || principalCents <= 0L) return 0L
        val monthlyRate = monthlyRate(annualRateBps)
        if (monthlyRate <= 0.0) return (principalCents.toDouble() / termMonths).roundToLong()
        val growth = (1.0 + monthlyRate).pow(termMonths)
        return (principalCents * monthlyRate * growth / (growth - 1.0)).roundToLong()
    }

    /** The payment being made: what was typed in, or the scheduled one when nothing was. */
    fun payment(terms: LoanTerms): Long =
        terms.paymentCents?.takeIf { it > 0L }
            ?: scheduledPayment(terms.principalCents, terms.annualRateBps, terms.termMonths)

    /**
     * The balance after [months] payments.
     *
     * Closed form rather than a loop, because the same expression answers "after 4 payments" and
     * "after 340" at the same cost, and a payment bigger than the scheduled one shortens the loan
     * for free — the balance simply reaches zero early, which is what [payoffMonths] then reports.
     */
    fun balanceAfter(terms: LoanTerms, months: Int): Long {
        if (months <= 0) return terms.principalCents
        val principal = terms.principalCents.toDouble()
        val payment = payment(terms).toDouble()
        val rate = monthlyRate(terms.annualRateBps)
        val balance = if (rate <= 0.0) {
            principal - payment * months
        } else {
            val growth = (1.0 + rate).pow(months)
            principal * growth - payment * (growth - 1.0) / rate
        }
        return balance.roundToLong().coerceAtLeast(0L)
    }

    /**
     * How many payments it takes to clear the debt, or null when the payment never will —
     * a payment that does not cover the month's interest is a balance that grows, and saying
     * "never" is the only honest answer to it.
     */
    fun payoffMonths(terms: LoanTerms): Int? {
        val principal = terms.principalCents.toDouble()
        if (principal <= 0.0) return 0
        // A note paid at its own scheduled payment clears in exactly its term. The formula would
        // say one month more, because a payment rounded down to whole cents leaves a few cents of
        // principal at the end — true of every mortgage ever written, and not what the paperwork
        // says. The term is the answer; only a payment the user changed is worth solving for.
        if (terms.termMonths > 0 &&
            (terms.paymentCents == null ||
                terms.paymentCents == scheduledPayment(terms.principalCents, terms.annualRateBps, terms.termMonths))
        ) {
            return terms.termMonths
        }
        val payment = payment(terms).toDouble()
        if (payment <= 0.0) return null
        val rate = monthlyRate(terms.annualRateBps)
        if (rate <= 0.0) return Math.ceil(principal / payment).toInt()
        if (payment <= principal * rate) return null
        val months = -ln(1.0 - rate * principal / payment) / ln(1.0 + rate)
        return Math.ceil(months - 1e-9).toInt()
    }

    /** Payments elapsed between [startEpochDay] and [asOfEpochDay], never negative. */
    fun paymentsMade(startEpochDay: Long, asOfEpochDay: Long): Int {
        if (asOfEpochDay <= startEpochDay) return 0
        val start = java.time.LocalDate.ofEpochDay(startEpochDay)
        val asOf = java.time.LocalDate.ofEpochDay(asOfEpochDay)
        return java.time.temporal.ChronoUnit.MONTHS.between(start, asOf).toInt().coerceAtLeast(0)
    }

    /** The whole picture on [asOfEpochDay], for a loan that started on [startEpochDay]. */
    fun snapshot(terms: LoanTerms, startEpochDay: Long?, asOfEpochDay: Long): LoanSnapshot {
        val made = if (startEpochDay == null) 0 else paymentsMade(startEpochDay, asOfEpochDay)
        val payoff = payoffMonths(terms)
        val effective = if (payoff != null) made.coerceAtMost(payoff) else made
        // The last scheduled payment clears the note. Carrying the few cents the cent-rounded
        // payment leaves behind would show a paid-off mortgage with $0.02 still on it.
        val balance = if (payoff != null && effective >= payoff) 0L else balanceAfter(terms, effective)
        val principalPaid = (terms.principalCents - balance).coerceAtLeast(0L)
        val paid = payment(terms) * effective
        return LoanSnapshot(
            paymentsMade = effective,
            balanceCents = balance,
            principalPaidCents = principalPaid,
            // Everything paid that did not reduce the debt was interest. It cannot go negative:
            // the balance is clamped at zero, so the final payment is never over-counted.
            interestPaidCents = (paid - principalPaid).coerceAtLeast(0L),
            paymentsRemaining = payoff?.let { (it - effective).coerceAtLeast(0) },
            scheduledPaymentCents = scheduledPayment(terms.principalCents, terms.annualRateBps, terms.termMonths)
        )
    }

    /** The month the last payment falls in, or null when the loan never clears. */
    fun payoffDate(terms: LoanTerms, startEpochDay: Long?): java.time.LocalDate? {
        val start = startEpochDay ?: return null
        val months = payoffMonths(terms) ?: return null
        return java.time.LocalDate.ofEpochDay(start).plusMonths(months.toLong())
    }

    /** What the thing is worth minus what is owed on it. Negative is a real answer, not an error. */
    fun equityCents(valueCents: Long?, balanceCents: Long): Long? =
        valueCents?.let { it - balanceCents }

    /** 625 basis points a year → the monthly rate the formulas above compound with. */
    private fun monthlyRate(annualRateBps: Int): Double = annualRateBps / 10_000.0 / 12.0

    /** 625 → "6.25%". */
    fun formatRate(annualRateBps: Int): String {
        val whole = annualRateBps / 100
        val fraction = annualRateBps % 100
        return if (fraction == 0) "$whole%" else "$whole.${fraction.toString().padStart(2, '0').trimEnd('0')}%"
    }

    /** "6.25" → 625. Rates are typed as percentages because that is how notes are written. */
    fun parseRate(raw: String): Int? {
        val value = raw.trim().removeSuffix("%").trim().toDoubleOrNull() ?: return null
        if (value < 0.0 || value > 100.0) return null
        return (value * 100).roundToLong().toInt()
    }
}
