package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The roll-ups, tested from the direction they double-count in.
 *
 * Every exclusion in [CashFlow] exists because leaving it out produces a number that is wrong in a
 * specific, plausible way, so each one gets a test that would fail loudly if somebody removed it.
 */
class CashFlowTest {

    private val from = LocalDate.parse("2026-08-01")
    private val to = LocalDate.parse("2026-08-31")

    @Test
    fun `in and out are magnitudes, and the net is the difference`() {
        val summary = CashFlow.summarise(
            listOf(
                txn("2026-08-01", 2_400.0, "PAYROLL", category = Category.INCOME),
                txn("2026-08-04", -140.0, "SAFEWAY", category = Category.GROCERIES),
                txn("2026-08-19", -60.0, "SAFEWAY", category = Category.GROCERIES)
            ),
            from, to
        )
        assertEquals(240_000L, summary.inCents)
        assertEquals(20_000L, summary.outCents)
        assertEquals(220_000L, summary.netCents)
    }

    @Test
    fun `moving money to savings is not spending, and is not income either`() {
        // Both halves of the transfer are connected, so without the exclusion the same $500 shows up
        // as $500 spent and $500 earned, and the month nets right while both totals are inflated.
        val summary = CashFlow.summarise(
            listOf(
                txn("2026-08-02", -500.0, "TO SAVINGS", category = Category.TRANSFER, transfer = true),
                txn("2026-08-02", 500.0, "FROM CHECKING", accountId = "sav", category = Category.TRANSFER, transfer = true)
            ),
            from, to
        )
        assertEquals(0L, summary.inCents)
        assertEquals(0L, summary.outCents)
    }

    @Test
    fun `paying the card is not spending on top of what the card bought`() {
        // $600 of groceries on the card is $600 of spending. The $600 that clears it is the same
        // money arriving where it was always going.
        val summary = CashFlow.summarise(
            listOf(
                txn("2026-08-04", -600.0, "SAFEWAY", accountId = "visa", category = Category.GROCERIES),
                txn("2026-08-20", -600.0, "CARD PAYMENT", accountId = "chk", category = Category.DEBT)
            ),
            from, to
        )
        assertEquals(60_000L, summary.outCents)
    }

    @Test
    fun `pending transactions are left out, because their amounts change`() {
        val summary = CashFlow.summarise(
            listOf(txn("2026-08-04", -140.0, "SAFEWAY", category = Category.GROCERIES, pending = true)),
            from, to
        )
        assertEquals(0L, summary.outCents)
    }

    @Test
    fun `transactions outside the window are not in the window`() {
        val rows = listOf(
            txn("2026-07-31", -100.0, "EARLY", category = Category.SHOPPING),
            txn("2026-08-01", -10.0, "EDGE", category = Category.SHOPPING),
            txn("2026-08-31", -20.0, "EDGE", category = Category.SHOPPING),
            txn("2026-09-01", -100.0, "LATE", category = Category.SHOPPING)
        )
        // Both ends are inclusive: a month runs from the 1st to the 31st, and dropping either edge
        // loses a day of spending every month.
        assertEquals(3_000L, CashFlow.summarise(rows, from, to).outCents)
    }

    @Test
    fun `categories come back largest first, with their counts`() {
        val summary = CashFlow.summarise(
            listOf(
                txn("2026-08-04", -140.0, "SAFEWAY", category = Category.GROCERIES),
                txn("2026-08-19", -60.0, "SAFEWAY", category = Category.GROCERIES),
                txn("2026-08-06", -900.0, "LANDLORD", category = Category.HOUSING)
            ),
            from, to
        )
        assertEquals(Category.HOUSING, summary.byCategory.first().category)
        val groceries = summary.byCategory.first { it.category == Category.GROCERIES }
        assertEquals(20_000L, groceries.amountCents)
        assertEquals(2, groceries.count)
        // $200 of $1,100 spent.
        assertEquals(0.1818, groceries.shareOf(summary.outCents), 1e-4)
        assertEquals(0.0, groceries.shareOf(0L), 1e-9)
    }

    @Test
    fun `the daily burn is the spend over the days in the period, both ends counted`() {
        val summary = CashFlow.summarise(
            listOf(txn("2026-08-04", -310.0, "SHOP", category = Category.SHOPPING)),
            from, to
        )
        assertEquals(31L, summary.days)
        assertEquals(1_000L, summary.dailyBurnCents)
    }

    @Test
    fun `a run of months comes back oldest first and includes the current one`() {
        val months = CashFlow.byMonth(emptyList(), LocalDate.parse("2026-09-09"), months = 3)
        assertEquals(3, months.size)
        assertEquals(LocalDate.parse("2026-07-01"), months.first().from)
        assertEquals(LocalDate.parse("2026-09-30"), months.last().to)
    }

    @Test
    fun `runway is cash over the burn rate, and ignores income by design`() {
        val summary = CashFlow.summarise(
            listOf(txn("2026-08-04", -3_100.0, "SHOP", category = Category.SHOPPING)),
            from, to
        )
        // $100 a day; $2,500 of cash lasts twenty-five days. A runway that assumed the next
        // paycheque arrives would not be a runway, it would be a budget.
        assertEquals(25L, CashFlow.runwayDays(250_000L, summary))
    }

    @Test
    fun `a household that spent nothing has no runway to quote, and no cash means no days`() {
        val nothing = CashFlow.summarise(emptyList(), from, to)
        assertNull("infinity is not a number to put on a screen", CashFlow.runwayDays(100_000L, nothing))
        assertEquals(0L, CashFlow.runwayDays(0L, nothing))
    }

    @Test
    fun `change is a fraction, and is refused when there was nothing to grow from`() {
        assertEquals(0.5, CashFlow.change(10_000L, 15_000L)!!, 1e-9)
        assertEquals(-0.25, CashFlow.change(10_000L, 7_500L)!!, 1e-9)
        assertNull("up from zero is not a percentage", CashFlow.change(0L, 5_000L))
    }

    @Test
    fun `the typical month is a median, so one new roof is not a habit`() {
        val months = listOf(
            CashFlow.summarise(listOf(txn("2026-06-04", -900.0, "L", category = Category.HOUSING)), LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30")),
            CashFlow.summarise(listOf(txn("2026-07-04", -900.0, "L", category = Category.HOUSING)), LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31")),
            CashFlow.summarise(listOf(txn("2026-08-04", -14_000.0, "ROOF", category = Category.HOUSING)), from, to)
        )
        assertEquals(90_000L, CashFlow.typicalMonthly(months, Category.HOUSING))
        assertEquals(0L, CashFlow.typicalMonthly(months, Category.TRAVEL))
    }

    @Test
    fun `a month with an annual premium in it nets negative, which is normal rather than a crisis`() {
        val summary = CashFlow.summarise(
            listOf(
                txn("2026-08-01", 2_400.0, "PAYROLL", category = Category.INCOME),
                txn("2026-08-03", -3_100.0, "USAA INSURANCE", category = Category.INSURANCE)
            ),
            from, to
        )
        assertTrue(summary.netCents < 0L)
    }
}
