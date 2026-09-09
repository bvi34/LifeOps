package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pairing the two halves of a transfer, which is what stops a month counting the same money twice.
 *
 * The tests that matter most here are the ones asserting it does **not** pair: an over-eager matcher
 * silently removes real spending from the totals, which is the same class of invisible wrongness the
 * whole module keeps arguing against.
 */
class TransfersTest {

    @Test
    fun `the two halves of one transfer are both flagged`() {
        val rows = listOf(
            txn("2026-09-02", -500.0, "TO SAVINGS", accountId = "chk", id = "out"),
            txn("2026-09-02", 500.0, "FROM CHECKING", accountId = "sav", id = "in")
        )
        assertEquals(setOf("out", "in"), Transfers.pairedIds(rows))
        assertTrue(Transfers.mark(rows).all { it.transfer })
    }

    @Test
    fun `a card payment is a transfer, which is what stops the groceries counting twice`() {
        // $600 of groceries on the card is $600 of spending; the $600 that clears the card is the
        // same money arriving where it was always going. The provider's category caught some of
        // these; pairing catches them because both accounts are held.
        val rows = listOf(
            txn("2026-09-20", -600.0, "ACH PMT 4821", accountId = "chk", id = "paid"),
            txn("2026-09-21", 600.0, "PAYMENT THANK YOU", accountId = "visa", id = "credited")
        )
        assertEquals(setOf("paid", "credited"), Transfers.pairedIds(rows))
    }

    @Test
    fun `a few days apart is still one transfer`() {
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT", accountId = "chk", id = "out"),
            txn("2026-09-05", 500.0, "IN", accountId = "sav", id = "in")
        )
        assertEquals(2, Transfers.pairedIds(rows).size)
    }

    @Test
    fun `a fortnight apart is two separate things that happen to be the same size`() {
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT", accountId = "chk", id = "out"),
            txn("2026-09-18", 500.0, "IN", accountId = "sav", id = "in")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
    }

    @Test
    fun `amounts must agree exactly - a transfer is one instruction`() {
        // Loosening this to a tolerance is what would start pairing a $50 shop with an unrelated
        // $50.40 refund, and quietly removing both from the month.
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT", accountId = "chk", id = "out"),
            txn("2026-09-02", 495.0, "IN", accountId = "sav", id = "in")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
    }

    @Test
    fun `two rows on one account are not two sides of anything`() {
        val rows = listOf(
            txn("2026-09-02", -40.0, "SHOP", accountId = "chk", id = "buy"),
            txn("2026-09-03", 40.0, "REFUND", accountId = "chk", id = "refund")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
    }

    @Test
    fun `spending is not paired with unrelated income of a different size`() {
        val rows = listOf(
            txn("2026-09-02", -140.0, "SAFEWAY", accountId = "chk", id = "shop"),
            txn("2026-09-03", 2_400.0, "PAYROLL", accountId = "chk", id = "pay")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
    }

    @Test
    fun `one credit settles one debit, never two`() {
        // Two $500 debits and one $500 credit: the money only came back once, so only one pairing
        // is real and the other debit stays real spending.
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT A", accountId = "chk", id = "outA"),
            txn("2026-09-02", -500.0, "OUT B", accountId = "chk", id = "outB"),
            txn("2026-09-03", 500.0, "IN", accountId = "sav", id = "in")
        )
        val paired = Transfers.pairedIds(rows)
        assertEquals(2, paired.size)
        assertTrue(paired.contains("in"))
    }

    @Test
    fun `pending rows are never paired, because their amounts change`() {
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT", accountId = "chk", id = "out", pending = true),
            txn("2026-09-02", 500.0, "IN", accountId = "sav", id = "in")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
    }

    @Test
    fun `a row the provider already called a transfer is left alone`() {
        // Nothing to gain from pairing something already excluded, and doing so would consume a
        // partner that some other row genuinely needs.
        val rows = listOf(
            txn("2026-09-02", -500.0, "OUT", accountId = "chk", id = "out", transfer = true),
            txn("2026-09-02", 500.0, "IN", accountId = "sav", id = "in")
        )
        assertTrue(Transfers.pairedIds(rows).isEmpty())
        assertTrue("but it stays flagged", Transfers.mark(rows).first { it.id == "out" }.transfer)
    }

    @Test
    fun `marking is what keeps the month from counting the money twice`() {
        val from = LocalDate.parse("2026-09-01")
        val to = LocalDate.parse("2026-09-30")
        val rows = listOf(
            txn("2026-09-02", -500.0, "TO SAVINGS", accountId = "chk", id = "out"),
            txn("2026-09-02", 500.0, "FROM CHECKING", accountId = "sav", id = "in"),
            txn("2026-09-04", -140.0, "SAFEWAY", accountId = "chk", category = Category.GROCERIES, id = "shop")
        )

        // Unmarked, the month claims $640 spent and $500 earned. Both wrong.
        val naive = CashFlow.summarise(rows, from, to)
        assertEquals(64_000L, naive.outCents)
        assertEquals(50_000L, naive.inCents)

        val corrected = CashFlow.summarise(Transfers.mark(rows), from, to)
        assertEquals(14_000L, corrected.outCents)
        assertEquals(0L, corrected.inCents)
    }

    @Test
    fun `a list with nothing to pair comes back untouched`() {
        val rows = listOf(txn("2026-09-04", -140.0, "SAFEWAY", accountId = "chk", id = "shop"))
        assertTrue(Transfers.pairedIds(rows).isEmpty())
        assertFalse(Transfers.mark(rows).single().transfer)
        assertTrue(Transfers.mark(emptyList()).isEmpty())
    }
}
