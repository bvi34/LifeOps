package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class LoanTest {

    /** $300,000 at 6.00% over 30 years — the textbook mortgage. */
    private val mortgage = LoanTerms(
        principalCents = 30_000_000L,
        annualRateBps = 600,
        termMonths = 360,
        escrowCents = 45_000L
    )

    @Test
    fun `the scheduled payment is the one on the note`() {
        assertEquals(179_865L, Loan.scheduledPayment(30_000_000L, 600, 360))
        assertEquals(179_865L, Loan.payment(mortgage))
        // Escrow rides along with the payment but is never amortised.
        assertEquals(224_865L, mortgage.totalMonthlyCents())
    }

    @Test
    fun `an interest-free loan is divided, not divided by zero`() {
        val terms = LoanTerms(principalCents = 1_200_000L, annualRateBps = 0, termMonths = 12)

        assertEquals(100_000L, Loan.payment(terms))
        assertEquals(900_000L, Loan.balanceAfter(terms, 3))
        assertEquals(12, Loan.payoffMonths(terms))
    }

    @Test
    fun `a year in, almost nothing has come off the principal`() {
        val balance = Loan.balanceAfter(mortgage, 12)

        // Within a cent of the closed-form figure; the point is the shape, not the rounding.
        assertTrue("balance was $balance", abs(balance - 29_631_598L) <= 2L)
        val snapshot = Loan.snapshot(mortgage, startEpochDay = LocalDate.of(2025, 1, 1).toEpochDay(), asOfEpochDay = LocalDate.of(2026, 1, 1).toEpochDay())
        assertEquals(12, snapshot.paymentsMade)
        assertTrue(snapshot.interestPaidCents > snapshot.principalPaidCents * 4)
        assertEquals(348, snapshot.paymentsRemaining)
    }

    @Test
    fun `paying more than the note asks clears it early`() {
        val early = mortgage.copy(paymentCents = 200_000L)

        assertEquals(278, Loan.payoffMonths(early))
        assertEquals(360, Loan.payoffMonths(mortgage))
        assertEquals(
            LocalDate.of(2025, 1, 1).plusMonths(278),
            Loan.payoffDate(early, LocalDate.of(2025, 1, 1).toEpochDay())
        )
    }

    @Test
    fun `a payment that never covers the interest never pays it off`() {
        val hopeless = mortgage.copy(paymentCents = 100_000L)

        assertNull(Loan.payoffMonths(hopeless))
        assertNull(Loan.payoffDate(hopeless, LocalDate.of(2025, 1, 1).toEpochDay()))
    }

    @Test
    fun `the last scheduled payment leaves nothing behind`() {
        val snapshot = Loan.snapshot(
            mortgage,
            startEpochDay = LocalDate.of(1995, 1, 1).toEpochDay(),
            asOfEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()
        )

        assertTrue(snapshot.isPaidOff)
        assertEquals(360, snapshot.paymentsMade)
        assertEquals(0, snapshot.paymentsRemaining)
        assertEquals(1f, snapshot.progress(mortgage.principalCents), 0.0001f)
    }

    @Test
    fun `a loan with no start date has simply not started`() {
        val snapshot = Loan.snapshot(mortgage, startEpochDay = null, asOfEpochDay = LocalDate.of(2026, 1, 1).toEpochDay())

        assertEquals(0, snapshot.paymentsMade)
        assertEquals(mortgage.principalCents, snapshot.balanceCents)
        assertEquals(0L, snapshot.interestPaidCents)
    }

    @Test
    fun `equity is what it is worth minus what is owed, and may be negative`() {
        assertEquals(10_368_402L, Loan.equityCents(40_000_000L, 29_631_598L))
        assertEquals(-1_631_598L, Loan.equityCents(28_000_000L, 29_631_598L))
        assertNull(Loan.equityCents(null, 29_631_598L))
    }

    @Test
    fun `rates are typed as percentages and kept as basis points`() {
        assertEquals(625, Loan.parseRate("6.25"))
        assertEquals(600, Loan.parseRate("6"))
        assertEquals(650, Loan.parseRate("6.5%"))
        assertNull(Loan.parseRate("prime"))
        assertNull(Loan.parseRate("120"))

        assertEquals("6.25%", Loan.formatRate(625))
        assertEquals("6%", Loan.formatRate(600))
        assertEquals("6.5%", Loan.formatRate(650))
    }
}
