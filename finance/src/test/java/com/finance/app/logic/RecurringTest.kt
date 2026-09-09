package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The detector, tested against the shapes that actually appear in a bank export — including the
 * ones it is supposed to *refuse*, which is where the cost of being wrong lands: a false positive
 * here puts a task on somebody's LifeOps week for a bill that does not exist.
 */
class RecurringTest {

    private fun detect(rows: List<Transaction>) = Recurring.detect(rows)

    @Test
    fun `a monthly subscription is found, with its cadence and its amount`() {
        val series = detect(monthlyRun("2026-01-15", 4, -15.49, "NETFLIX.COM 8667169929")).single()
        assertEquals(Recurring.Cadence.MONTHLY, series.cadence)
        assertEquals(1_549L, series.typicalAmountCents)
        assertEquals(4, series.occurrences)
        assertEquals(LocalDate.parse("2026-04-15"), series.lastSeen)
        assertTrue("a subscription is the same every time", series.fixed)
    }

    @Test
    fun `two occurrences are a coincidence, not a series`() {
        // The cost of getting this wrong is a task on somebody's week for a bill that isn't real.
        assertTrue(detect(monthlyRun("2026-02-01", 2, -60.0, "SOME SHOP")).isEmpty())
    }

    @Test
    fun `a bill that moves about is still found, and says it moves about`() {
        // A gas bill through a winter: $180, $210, $160, $195. Same payee, same day, real spread.
        val rows = listOf(
            txn("2026-01-08", -180.0, "CITY UTILITIES 0108", category = Category.UTILITIES),
            txn("2026-02-08", -210.0, "CITY UTILITIES 0208", category = Category.UTILITIES),
            txn("2026-03-08", -160.0, "CITY UTILITIES 0308", category = Category.UTILITIES),
            txn("2026-04-08", -195.0, "CITY UTILITIES 0408", category = Category.UTILITIES)
        )
        val series = detect(rows).single()
        assertEquals(Recurring.Cadence.MONTHLY, series.cadence)
        assertEquals(Category.UTILITIES, series.category)
        assertTrue("a utility bill is never the same twice", !series.fixed)
        assertTrue(series.variability > 0.02)
    }

    @Test
    fun `an annual charge among the monthlies does not destroy the monthly series`() {
        // The case the filter order exists for: a $45 gym plus one $180 renewal mid-March. Checking
        // the rhythm first would see 31, 28, 15, 16, 30, 31 and give up on a plainly monthly payee.
        val rows = monthlyRun("2026-01-05", 6, -45.0, "CITY GYM") +
            txn("2026-03-20", -180.0, "CITY GYM", id = "renewal")
        val series = detect(rows).single()
        assertEquals(Recurring.Cadence.MONTHLY, series.cadence)
        assertEquals(4_500L, series.typicalAmountCents)
        assertEquals(6, series.occurrences)
    }

    @Test
    fun `irregular visits to the same shop are not a series`() {
        val rows = listOf(
            txn("2026-01-03", -12.40, "BLUE BOTTLE"),
            txn("2026-01-19", -8.75, "BLUE BOTTLE"),
            txn("2026-02-27", -14.10, "BLUE BOTTLE"),
            txn("2026-03-02", -9.90, "BLUE BOTTLE")
        )
        assertTrue(detect(rows).isEmpty())
    }

    @Test
    fun `weekly, fortnightly, quarterly and yearly are each recognised`() {
        assertEquals(
            Recurring.Cadence.WEEKLY,
            detect(run("2026-01-05", 5, -32.0, 7, "WEEKLY CLEANER")).single().cadence
        )
        assertEquals(
            Recurring.Cadence.BIWEEKLY,
            detect(run("2026-01-05", 5, -60.0, 14, "LAWN SERVICE")).single().cadence
        )
        assertEquals(
            Recurring.Cadence.QUARTERLY,
            detect(run("2026-01-05", 4, -420.0, 91, "WATER DISTRICT")).single().cadence
        )
        assertEquals(
            Recurring.Cadence.ANNUAL,
            detect(run("2023-03-01", 3, -99.0, 365, "DOMAIN RENEWAL")).single().cadence
        )
    }

    @Test
    fun `income is never detected - a projected paycheque is a promise the app cannot keep`() {
        val payroll = monthlyRun("2026-01-15", 6, 2_400.0, "ACME PAYROLL", category = Category.INCOME)
        assertTrue(detect(payroll).isEmpty())
    }

    @Test
    fun `standing transfers into savings are regular and are not bills`() {
        val saving = monthlyRun("2026-01-01", 6, -500.0, "TRANSFER TO SAVINGS")
            .map { it.copy(transfer = true) }
        assertTrue(detect(saving).isEmpty())
    }

    @Test
    fun `pending charges do not build a series`() {
        val rows = monthlyRun("2026-01-15", 4, -15.49, "NETFLIX.COM").map { it.copy(pending = true) }
        assertTrue(detect(rows).isEmpty())
    }

    @Test
    fun `the same payee on two accounts is two series, because they are two obligations`() {
        val rows = monthlyRun("2026-01-15", 4, -15.49, "NETFLIX.COM", accountId = "chk") +
            monthlyRun("2026-01-20", 4, -15.49, "NETFLIX.COM", accountId = "visa")
        assertEquals(setOf("chk", "visa"), detect(rows).map { it.accountId }.toSet())
    }

    @Test
    fun `the label shown is the shortest name seen, not the one with the reference number on it`() {
        val rows = listOf(
            txn("2026-01-15", -15.49, "NETFLIX.COM 8667169929 CA", merchant = "Netflix"),
            txn("2026-02-15", -15.49, "NETFLIX.COM 8667169929 CA", merchant = "Netflix"),
            txn("2026-03-15", -15.49, "NETFLIX.COM 8667169929 CA", merchant = "Netflix")
        )
        assertEquals("Netflix", detect(rows).single().label)
    }

    @Test
    fun `the next date steps by calendar months, so a monthly bill keeps its day`() {
        val series = detect(monthlyRun("2026-01-15", 3, -22.0, "SOME BILL")).single()
        // Six steps on from the last charge. By calendar months that is still the 15th; by "every
        // 30 days" it would have walked to the 10th, and a bill this app claims is due on the 10th
        // when the biller takes it on the 15th is worse than no prediction at all.
        assertEquals(LocalDate.parse("2026-09-15"), series.nextAfter(LocalDate.parse("2026-08-20")))
    }

    @Test
    fun `the next date is always after the day asked about, however stale the series is`() {
        val series = detect(monthlyRun("2024-01-15", 4, -15.49, "NETFLIX.COM")).single()
        val next = series.nextAfter(LocalDate.parse("2026-09-09"))
        assertTrue("a two-year-old series still steps forward to the future", next.isAfter(LocalDate.parse("2026-09-09")))
        assertEquals(15, next.dayOfMonth)
    }

    @Test
    fun `a ten-day gap picks the closest cadence rather than the first one declared`() {
        // Within tolerance of both weekly and fortnightly; the answer must not depend on enum order.
        assertEquals(Recurring.Cadence.WEEKLY, Recurring.Cadence.forGap(10))
        assertEquals(Recurring.Cadence.BIWEEKLY, Recurring.Cadence.forGap(12))
        assertNull("a 60-day gap is no cadence this app knows", Recurring.Cadence.forGap(60))
        assertNotNull(Recurring.Cadence.forGap(29))
    }

    @Test
    fun `longer cadences get proportionally more slack`() {
        // A yearly premium billed on "the first Monday of March" moves several days between years.
        assertEquals(Recurring.Cadence.ANNUAL, Recurring.Cadence.forGap(371))
        assertEquals(Recurring.Cadence.QUARTERLY, Recurring.Cadence.forGap(89))
    }

    @Test
    fun `the median averages the two middles for an even count`() {
        assertEquals(3L, Recurring.median(listOf(1L, 5L, 2L, 4L)))
        assertEquals(2L, Recurring.median(listOf(1L, 2L, 3L)))
    }
}
