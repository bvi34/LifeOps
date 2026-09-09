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
    fun `a real Plaid statement carries no payee key, and still hides the duplicate`() {
        // The shape PlaidJson actually produces: a liability names the *account*, not the string the
        // payment leaves checking under, so merchantKey is null. Matching on keys alone meant this
        // never fired and every connected card showed its bill twice.
        val series = Recurring.detect(monthlyRun("2026-06-12", 4, -420.0, "USAA CARD PAYMENT"))
        val fromIssuer = bill(
            payee = "USAA Rewards Visa",
            due = "2026-10-14",
            amount = 420.0,
            source = Bills.Source.STATEMENT,
            merchantKey = null
        )
        assertTrue(
            "the issuer's word wins; the prediction is the same money seen twice",
            Bills.predict(series, today, 45L, existing = listOf(fromIssuer)).isEmpty()
        )
    }

    @Test
    fun `an unrelated bill of a different size is not swallowed by that`() {
        // The amount test only stands in for a missing key, so it must not merge two real payees who
        // happen to fall due in the same fortnight.
        val series = Recurring.detect(monthlyRun("2026-06-12", 4, -420.0, "USAA CARD PAYMENT"))
        val unrelated = bill(
            payee = "Some other card",
            due = "2026-10-14",
            amount = 38.0,
            source = Bills.Source.STATEMENT,
            merchantKey = null
        )
        assertEquals(1, Bills.predict(series, today, 45L, existing = listOf(unrelated)).size)
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

/**
 * Repeating manual bills — the one source that has to mint its own next occurrence, because nobody
 * else is going to tell us the rent is due again.
 */
class ManualBillTest {

    private val today = LocalDate.parse("2026-09-09")

    private fun rent(
        due: String,
        months: Int? = 1,
        paidOn: String? = null
    ) = bill(
        payee = "Landlord",
        due = due,
        amount = 1_400.0,
        source = Bills.Source.MANUAL,
        recurrenceMonths = months,
        paidOn = paidOn,
        id = Bills.manualId("acct", Merchants.key("Landlord"), LocalDate.parse(due))
    )

    @Test
    fun `a repeating manual bill mints the occurrences it owes, up to the horizon`() {
        val minted = Bills.rollForward(listOf(rent("2026-09-01")), today, horizonDays = 100L)
        assertEquals(
            listOf("2026-10-01", "2026-11-01", "2026-12-01").map(LocalDate::parse),
            minted.map { it.dueDate }
        )
        assertTrue("a fresh occurrence owes nothing yet", minted.none { it.paid })
    }

    @Test
    fun `a one-off manual bill does not repeat`() {
        assertTrue(Bills.rollForward(listOf(rent("2026-09-01", months = null)), today, 100L).isEmpty())
    }

    @Test
    fun `only manual bills roll forward - the others are told their next date`() {
        val statement = bill(
            payee = "USAA Visa", due = "2026-09-12", amount = 1_240.0,
            source = Bills.Source.STATEMENT, recurrenceMonths = 1
        )
        val predicted = bill(
            payee = "Netflix", due = "2026-09-12", amount = 15.49,
            source = Bills.Source.PREDICTED, recurrenceMonths = 1
        )
        assertTrue(Bills.rollForward(listOf(statement, predicted), today, 100L).isEmpty())
    }

    @Test
    fun `running it twice mints nothing the second time`() {
        // The ids are derived from the date, so "the same occurrence" is the same row by
        // construction. This is what lets the roll run as often as anything else in the module.
        val first = Bills.rollForward(listOf(rent("2026-09-01")), today, 100L)
        val second = Bills.rollForward(listOf(rent("2026-09-01")) + first, today, 100L)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `it steps from the last occurrence, not from today`() {
        // Somebody who stops opening the app for three months should come back to a rent bill for
        // each of those months — visibly unpaid, which is true — rather than to one dated today
        // that quietly pretends the gap didn't happen.
        val minted = Bills.rollForward(listOf(rent("2026-06-01")), today, horizonDays = 30L)
        assertEquals(
            listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").map(LocalDate::parse),
            minted.map { it.dueDate }
        )
    }

    @Test
    fun `a quarterly manual bill steps by quarters`() {
        val estimate = bill(
            payee = "IRS estimate", due = "2026-06-15", amount = 2_100.0,
            source = Bills.Source.MANUAL, recurrenceMonths = 3,
            id = Bills.manualId("acct", Merchants.key("IRS estimate"), LocalDate.parse("2026-06-15"))
        )
        val minted = Bills.rollForward(listOf(estimate), today, horizonDays = 120L)
        assertEquals(
            listOf("2026-09-15", "2026-12-15").map(LocalDate::parse),
            minted.map { it.dueDate }
        )
    }

    @Test
    fun `two payees on one account are two series`() {
        val minted = Bills.rollForward(
            listOf(
                rent("2026-09-01"),
                bill(
                    payee = "Storage unit", due = "2026-09-20", amount = 90.0,
                    source = Bills.Source.MANUAL, recurrenceMonths = 1,
                    id = Bills.manualId("acct", Merchants.key("Storage unit"), LocalDate.parse("2026-09-20"))
                )
            ),
            today, horizonDays = 45L
        )
        assertEquals(setOf("Landlord", "Storage unit"), minted.map { it.payee }.toSet())
    }

    @Test
    fun `a corrupt recurrence cannot spin`() {
        // Zero months would never advance the date. The filter refuses it outright rather than
        // relying on the step bound to notice.
        assertTrue(Bills.rollForward(listOf(rent("2026-09-01", months = 0)), today, 100L).isEmpty())
    }

    @Test
    fun `a typed payee settles against the bank's own wording for it`() {
        // The one case where the key was typed by a person rather than derived from a bank string.
        // Somebody writes "Landlord"; the bank says "LANDLORD SEPT AUTOPAY". Exact matching would
        // mean a typed bill never settles itself and rent gets ticked off by hand forever.
        val settled = Bills.settle(
            listOf(rent("2026-09-01")),
            listOf(txn("2026-09-02", -1_400.0, "LANDLORD SEPT AUTOPAY"))
        )
        assertEquals(LocalDate.parse("2026-09-02"), settled.single().paidOn)
    }

    @Test
    fun `a loose prefix is still not a match`() {
        // "LAND" must not settle a landscaping invoice just because the amount happens to fit.
        val settled = Bills.settle(
            listOf(rent("2026-09-01")),
            listOf(txn("2026-09-02", -1_400.0, "LANDSCAPING CO"))
        )
        assertNull(settled.single().paidOn)
    }

    @Test
    fun `the relaxation applies to typed bills only`() {
        // A predicted bill's key is derived from bank strings by construction, so it has no excuse
        // for being approximate — and loosening it there would start merging real payees.
        val predicted = bill(
            payee = "LANDLORD", due = "2026-09-01", amount = 1_400.0,
            source = Bills.Source.PREDICTED
        )
        val settled = Bills.settle(
            listOf(predicted),
            listOf(txn("2026-09-02", -1_400.0, "LANDLORD SEPT AUTOPAY"))
        )
        assertNull(settled.single().paidOn)
    }
}
