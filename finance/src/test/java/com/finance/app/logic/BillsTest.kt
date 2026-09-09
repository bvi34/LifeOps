package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BillsTest {

    private val today = LocalDate.parse("2026-09-09")

    @Test
    fun `status reads from the date, and a week out is this week's problem`() {
        assertEquals(Bills.Status.OVERDUE, bill(due = "2026-09-01", amount = 40.0).status(today))
        assertEquals(Bills.Status.DUE_SOON, bill(due = "2026-09-12", amount = 40.0).status(today))
        assertEquals(Bills.Status.UPCOMING, bill(due = "2026-10-01", amount = 40.0).status(today))
        assertEquals(
            Bills.Status.PAID,
            bill(due = "2026-09-01", amount = 40.0, paidOn = "2026-08-31").status(today)
        )
    }

    @Test
    fun `a payment near the due date settles the bill`() {
        val bills = listOf(bill(payee = "CITY UTILITIES", due = "2026-09-10", amount = 180.0))
        val settled = Bills.settle(
            bills,
            listOf(txn("2026-09-08", -178.40, "CITY UTILITIES 0908"))
        )
        assertEquals(LocalDate.parse("2026-09-08"), settled.single().paidOn)
    }

    @Test
    fun `a payment to somebody else does not settle it`() {
        val bills = listOf(bill(payee = "CITY UTILITIES", due = "2026-09-10", amount = 180.0))
        val settled = Bills.settle(bills, listOf(txn("2026-09-08", -180.0, "SOME OTHER PAYEE")))
        assertNull(settled.single().paidOn)
    }

    @Test
    fun `a payment outside the window does not settle it`() {
        val bills = listOf(bill(payee = "CITY UTILITIES", due = "2026-09-10", amount = 180.0))
        val settled = Bills.settle(bills, listOf(txn("2026-08-25", -180.0, "CITY UTILITIES 0825")))
        assertNull(settled.single().paidOn)
    }

    @Test
    fun `one payment settles one bill, not both bills to the same payee`() {
        // Two months of the same bill in flight. If one payment settled both, next month's would
        // silently disappear off the week.
        val bills = listOf(
            bill(payee = "CITY UTILITIES", due = "2026-09-10", amount = 180.0, id = "sep"),
            bill(payee = "CITY UTILITIES", due = "2026-09-14", amount = 180.0, id = "oct")
        )
        val settled = Bills.settle(bills, listOf(txn("2026-09-09", -180.0, "CITY UTILITIES 0909")))
        assertEquals(1, settled.count { it.paid })
        // And it settles the older one — a payment belongs to the bill that came first.
        assertTrue(settled.first { it.paid }.id == "sep")
    }

    @Test
    fun `a card payment matches on amount alone, because the transfer's name says nothing`() {
        // "Pay the Visa" leaves checking as some ACH string with no relationship to the card's name.
        val statement = bill(
            payee = "USAA Visa",
            due = "2026-09-12",
            amount = 1_240.0,
            minimum = 35.0,
            source = Bills.Source.STATEMENT,
            merchantKey = null
        )
        val settled = Bills.settle(listOf(statement), listOf(txn("2026-09-11", -1_240.0, "ACH PMT 4821")))
        assertTrue(settled.single().paid)
    }

    @Test
    fun `paying at least the minimum counts as paying the card`() {
        val statement = bill(
            payee = "USAA Visa",
            due = "2026-09-12",
            amount = 1_240.0,
            minimum = 35.0,
            source = Bills.Source.STATEMENT,
            merchantKey = null
        )
        // $400 is nowhere near the $1,240 balance but is well over the minimum: the card was paid.
        val settled = Bills.settle(listOf(statement), listOf(txn("2026-09-11", -400.0, "ACH PMT 4821")))
        assertTrue(settled.single().paid)
    }

    @Test
    fun `pending payments do not settle anything - their amounts change`() {
        val bills = listOf(bill(payee = "CITY UTILITIES", due = "2026-09-10", amount = 180.0))
        val settled = Bills.settle(
            bills,
            listOf(txn("2026-09-09", -180.0, "CITY UTILITIES 0909", pending = true))
        )
        assertNull(settled.single().paidOn)
    }

    @Test
    fun `predicting turns series into bills inside the horizon`() {
        // Charged on the 12th since May; the last one landed before today, so the next is September's.
        val series = Recurring.detect(monthlyRun("2026-05-12", 4, -15.49, "NETFLIX.COM"))
        val predicted = Bills.predict(series, today = today, horizonDays = 45L)
        assertEquals(1, predicted.size)
        assertEquals(LocalDate.parse("2026-09-12"), predicted.single().dueDate)
        assertEquals(Bills.Source.PREDICTED, predicted.single().source)
    }

    @Test
    fun `a prediction is dropped when a statement already covers the same obligation`() {
        // Connect the card and the statement gives a real due date; the same payment also shows up
        // in checking as a textbook monthly series. Showing both is showing one bill twice.
        val series = Recurring.detect(monthlyRun("2026-06-12", 4, -420.0, "USAA MORTGAGE"))
        val statement = bill(
            payee = "USAA Mortgage",
            due = "2026-10-15",
            amount = 420.0,
            source = Bills.Source.STATEMENT,
            merchantKey = Merchants.key("USAA MORTGAGE")
        )
        val predicted = Bills.predict(series, today, 45L, existing = listOf(statement))
        assertTrue("the statement wins", predicted.isEmpty())
    }

    @Test
    fun `a prediction well clear of the statement is a different obligation and survives`() {
        val series = Recurring.detect(monthlyRun("2026-06-12", 4, -420.0, "USAA MORTGAGE"))
        val unrelated = bill(
            payee = "USAA Mortgage",
            due = "2026-09-10",
            amount = 420.0,
            source = Bills.Source.STATEMENT,
            merchantKey = Merchants.key("USAA MORTGAGE")
        )
        // Over a fortnight apart from the 12th of October — not the same bill.
        assertEquals(1, Bills.predict(series, today, 45L, existing = listOf(unrelated)).size)
    }

    @Test
    fun `the list leads with overdue, then runs by date`() {
        val bills = listOf(
            bill(payee = "Later", due = "2026-09-30", amount = 10.0),
            bill(payee = "Overdue", due = "2026-09-02", amount = 10.0),
            bill(payee = "Soon", due = "2026-09-11", amount = 10.0)
        )
        assertEquals(
            listOf("Overdue", "Soon", "Later"),
            Bills.upcoming(bills, today).map { it.payee }
        )
    }

    @Test
    fun `a paid bill stays in the list rather than vanishing as the month goes on`() {
        val bills = listOf(bill(payee = "Insurance", due = "2026-09-03", amount = 140.0, paidOn = "2026-09-03"))
        assertEquals(1, Bills.upcoming(bills, today).size)
    }

    @Test
    fun `an unpaid bill nobody matched does not quietly age out`() {
        // Either somebody genuinely missed it, or the prediction was wrong. Both are worth seeing.
        val month = bill(payee = "Missed", due = "2026-08-05", amount = 60.0)
        assertEquals(1, Bills.upcoming(listOf(month), today).size)
        // But a year later it has stopped being news.
        val ancient = bill(payee = "Ancient", due = "2025-08-05", amount = 60.0)
        assertTrue(Bills.upcoming(listOf(ancient), today).isEmpty())
    }

    @Test
    fun `bills beyond the horizon are not shown`() {
        val far = bill(payee = "Far", due = "2026-12-25", amount = 10.0)
        assertTrue(Bills.upcoming(listOf(far), today, horizonDays = 45L).isEmpty())
    }

    @Test
    fun `committed counts what is still owed in the window, and nothing already paid`() {
        val bills = listOf(
            bill(payee = "A", due = "2026-09-12", amount = 100.0),
            bill(payee = "B", due = "2026-09-20", amount = 250.0),
            bill(payee = "C", due = "2026-09-15", amount = 400.0, paidOn = "2026-09-08"),
            bill(payee = "D", due = "2026-11-01", amount = 900.0)
        )
        assertEquals(
            35_000L,
            Bills.committedCents(bills, today, through = LocalDate.parse("2026-09-30"))
        )
    }

    @Test
    fun `sources resolve by key and an unknown one is the least trusted, not a crash`() {
        assertEquals(Bills.Source.STATEMENT, Bills.Source.fromKey("statement"))
        assertEquals(Bills.Source.PREDICTED, Bills.Source.fromKey("from-a-later-version"))
        assertFalse(bill(due = "2026-09-12", amount = 10.0).paid)
    }
}
