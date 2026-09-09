package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Plaid parser, run over payloads shaped like the real ones.
 *
 * This is the reason the parsing lives in `logic/` rather than inside the HTTP client: reading these
 * responses correctly has consequences — a sign flipped the wrong way makes a household look
 * bankrupt, a missing truncation writes an account number into a backup — and the way to be sure is
 * to run the code over the bytes on a JVM, not to squint at a screen on a phone.
 */
class PlaidJsonTest {

    private val today = LocalDate.parse("2026-09-09")

    /** Ours-from-theirs, as the sync and liabilities calls need it. */
    private val resolve: (String) -> String? = { providerId ->
        if (providerId.startsWith("plaid-")) "conn:$providerId" else null
    }

    @Test
    fun `accounts parse with their balances, kinds and masks`() {
        val json = """
            {
              "accounts": [
                {
                  "account_id": "plaid-chk",
                  "balances": {"available": 2400.0, "current": 2410.55, "limit": null, "iso_currency_code": "USD"},
                  "mask": "0123",
                  "name": "USAA Classic Checking",
                  "official_name": "USAA CLASSIC CHECKING",
                  "subtype": "checking",
                  "type": "depository"
                },
                {
                  "account_id": "plaid-visa",
                  "balances": {"available": 4160.0, "current": 840.0, "limit": 5000.0, "iso_currency_code": "USD"},
                  "mask": "9876",
                  "name": "USAA Rewards Visa",
                  "official_name": null,
                  "subtype": "credit card",
                  "type": "credit"
                }
              ],
              "item": {"item_id": "item-1"},
              "request_id": "abc"
            }
        """.trimIndent()

        val accounts = PlaidJson.accounts(json, connectionId = "conn")
        assertEquals(2, accounts.size)

        val checking = accounts.first()
        assertEquals("conn:plaid-chk", checking.id)
        assertEquals(AccountKind.DEPOSITORY, checking.kind)
        assertEquals(241_055L, checking.balance.currentCents)
        assertEquals(240_000L, checking.balance.availableCents)
        assertEquals("USAA Classic Checking ••0123", checking.displayName())

        val card = accounts.last()
        assertEquals(AccountKind.CREDIT, card.kind)
        assertEquals(84_000L, card.balance.currentCents)
        assertEquals(500_000L, card.balance.limitCents)
        // The card's "available" is headroom to borrow. It is stored, and it is not planned with.
        assertEquals(84_000L, card.balance.spendable(card.kind))
    }

    @Test
    fun `an account id is derived from the connection, so a refresh lands on the same row`() {
        val json = """{"accounts":[{"account_id":"plaid-chk","name":"A","type":"depository","balances":{"current":1.0}}]}"""
        val first = PlaidJson.accounts(json, "conn").single().id
        val second = PlaidJson.accounts(json, "conn").single().id
        assertEquals("a random id would orphan every transaction on every refresh", first, second)
    }

    @Test
    fun `an account with no id is dropped rather than given one we invented`() {
        val json = """{"accounts":[{"name":"Nameless","type":"depository","balances":{"current":1.0}}]}"""
        assertTrue(PlaidJson.accounts(json, "conn").isEmpty())
    }

    @Test
    fun `garbage is an empty answer, not an exception out of a parser`() {
        assertTrue(PlaidJson.accounts("not json at all", "conn").isEmpty())
        assertTrue(PlaidJson.accounts("{}", "conn").isEmpty())
        assertNull(PlaidJson.sync("<html>502 Bad Gateway</html>") { null })
        assertTrue(PlaidJson.liabilities("[1,2,3]", { null }, today).isEmpty())
    }

    @Test
    fun `the transaction sign is flipped exactly once, here`() {
        val json = """
            {
              "added": [
                {
                  "transaction_id": "t-1", "account_id": "plaid-chk",
                  "amount": 40.0, "date": "2026-09-04", "authorized_date": "2026-09-03",
                  "name": "SAFEWAY 1042", "merchant_name": "Safeway", "pending": false,
                  "personal_finance_category": {"primary": "FOOD_AND_DRINK", "detailed": "FOOD_AND_DRINK_GROCERIES"}
                },
                {
                  "transaction_id": "t-2", "account_id": "plaid-chk",
                  "amount": -2400.0, "date": "2026-09-01",
                  "name": "ACME PAYROLL", "merchant_name": null, "pending": false,
                  "personal_finance_category": {"primary": "INCOME", "detailed": "INCOME_WAGES"}
                }
              ],
              "modified": [], "removed": [], "next_cursor": "cursor-2", "has_more": false
            }
        """.trimIndent()

        val sync = PlaidJson.sync(json, resolve)!!
        val shop = sync.added.first { it.providerTransactionId == "t-1" }
        val pay = sync.added.first { it.providerTransactionId == "t-2" }

        // Plaid: positive is money out. Us: negative is money out.
        assertEquals(-4_000L, shop.amountCents)
        assertTrue(shop.outflow)
        assertEquals(240_000L, pay.amountCents)
        assertTrue(pay.inflow)

        assertEquals(Category.GROCERIES, shop.category)
        assertEquals("Safeway", shop.merchant)
        assertEquals(LocalDate.parse("2026-09-04"), shop.date)
        assertEquals("cursor-2", sync.nextCursor)
        assertFalse(sync.hasMore)
    }

    @Test
    fun `the posted date wins over the authorised one`() {
        // A weekend can put three days between them, and a statement is posted dates.
        val json = """
            {"added":[{"transaction_id":"t","account_id":"plaid-chk","amount":1.0,
              "date":"2026-09-07","authorized_date":"2026-09-04","name":"X"}],
             "removed":[],"next_cursor":"c","has_more":false}
        """.trimIndent()
        assertEquals(LocalDate.parse("2026-09-07"), PlaidJson.sync(json, resolve)!!.added.single().date)
    }

    @Test
    fun `sync reports removals, which is why it is used instead of transactions get`() {
        // Banks do un-post transactions. An app built on /transactions/get never hears about it and
        // shows a phantom charge forever.
        val json = """
            {"added":[],"modified":[],"removed":[{"transaction_id":"gone-1"},{"transaction_id":"gone-2"}],
             "next_cursor":"c","has_more":true}
        """.trimIndent()
        val sync = PlaidJson.sync(json, resolve)!!
        assertEquals(listOf("gone-1", "gone-2"), sync.removedProviderIds)
        assertTrue(sync.hasMore)
    }

    @Test
    fun `a transaction on an account we do not hold is dropped rather than orphaned`() {
        val json = """
            {"added":[{"transaction_id":"t","account_id":"unknown-account","amount":1.0,
              "date":"2026-09-07","name":"X"}],"removed":[],"next_cursor":"c","has_more":false}
        """.trimIndent()
        assertTrue(PlaidJson.sync(json, resolve)!!.added.isEmpty())
    }

    @Test
    fun `a card's statement gives a real due date, and the balance rather than the minimum`() {
        val json = """
            {
              "accounts": [{"account_id":"plaid-visa","name":"USAA Rewards Visa","type":"credit","balances":{"current":1240.0}}],
              "liabilities": {
                "credit": [{
                  "account_id": "plaid-visa",
                  "last_statement_balance": 1240.55,
                  "minimum_payment_amount": 35.0,
                  "next_payment_due_date": "2026-09-28",
                  "is_overdue": false
                }],
                "mortgage": [], "student": []
              }
            }
        """.trimIndent()

        val bill = PlaidJson.liabilities(json, resolve, today).single()
        assertEquals(Bills.Source.STATEMENT, bill.source)
        assertEquals(LocalDate.parse("2026-09-28"), bill.dueDate)
        // Leading with the minimum is how a balance becomes permanent, so the app leads with the
        // balance and keeps the minimum beside it.
        assertEquals(124_055L, bill.amountCents)
        assertEquals(3_500L, bill.minimumCents)
        assertEquals("USAA Rewards Visa", bill.payee)
        assertEquals(Category.DEBT, bill.category)
    }

    @Test
    fun `a mortgage and a student loan carry their amounts in different fields`() {
        val json = """
            {
              "accounts": [
                {"account_id":"plaid-mtg","name":"USAA Mortgage","type":"loan","balances":{"current":242000.0}},
                {"account_id":"plaid-edu","name":"Student Loan","type":"loan","balances":{"current":8200.0}}
              ],
              "liabilities": {
                "credit": [],
                "mortgage": [{"account_id":"plaid-mtg","next_monthly_payment":2140.34,"next_payment_due_date":"2026-09-15"}],
                "student": [{"account_id":"plaid-edu","minimum_payment_amount":125.0,"next_payment_due_date":"2026-09-21"}]
              }
            }
        """.trimIndent()

        val bills = PlaidJson.liabilities(json, resolve, today).sortedBy { it.dueDate }
        assertEquals(2, bills.size)
        assertEquals(214_034L, bills.first().amountCents)
        assertEquals(Category.HOUSING, bills.first().category)
        assertEquals(12_500L, bills.last().amountCents)
        assertEquals(Category.DEBT, bills.last().category)
    }

    @Test
    fun `a stale due date is a stale field, not a bill somebody is months late on`() {
        // Plaid keeps reporting last cycle's date for a while after it passes. Publishing it as
        // overdue would be the app crying wolf on its most trusted source.
        val json = """
            {"accounts":[{"account_id":"plaid-visa","name":"Visa","type":"credit","balances":{"current":10.0}}],
             "liabilities":{"credit":[{"account_id":"plaid-visa","last_statement_balance":100.0,
               "minimum_payment_amount":25.0,"next_payment_due_date":"2026-06-01"}]}}
        """.trimIndent()
        assertTrue(PlaidJson.liabilities(json, resolve, today).isEmpty())

        // A fortnight late is a real bill and is kept.
        val recent = json.replace("2026-06-01", "2026-08-26")
        assertEquals(1, PlaidJson.liabilities(recent, resolve, today).size)
    }

    @Test
    fun `a liability with no amount at all is not a bill`() {
        val json = """
            {"accounts":[{"account_id":"plaid-visa","name":"Visa","type":"credit","balances":{"current":0.0}}],
             "liabilities":{"credit":[{"account_id":"plaid-visa","next_payment_due_date":"2026-09-28"}]}}
        """.trimIndent()
        assertTrue(PlaidJson.liabilities(json, resolve, today).isEmpty())
    }

    @Test
    fun `link and exchange come back as tokens`() {
        assertEquals(
            "link-production-abc",
            PlaidJson.linkToken("""{"link_token":"link-production-abc","expiration":"2026-09-09T12:00:00Z"}""")
        )
        assertNull(PlaidJson.linkToken("""{"link_token":""}"""))

        val exchange = PlaidJson.exchange("""{"access_token":"access-production-xyz","item_id":"item-9"}""")!!
        assertEquals("access-production-xyz", exchange.accessToken)
        assertEquals("item-9", exchange.itemId)
        assertNull(PlaidJson.exchange("""{"request_id":"r"}"""))
    }

    @Test
    fun `needing to log in again is not a broken connection`() {
        val json = """
            {"error_type":"ITEM_ERROR","error_code":"ITEM_LOGIN_REQUIRED",
             "error_message":"the login details of this item have changed",
             "display_message":"Please sign in to your bank again.","request_id":"r"}
        """.trimIndent()
        val error = PlaidJson.error(json)!!
        assertTrue("this happens every few months and needs a prompt, not a failure", error.reauth)
        assertFalse(error.transient)
        // Plaid writes one message for end users and one for developers. Show theirs.
        assertEquals("Please sign in to your bank again.", error.message)
    }

    @Test
    fun `an outage is worth retrying and is nobody's fault`() {
        val json = """{"error_type":"API_ERROR","error_code":"INSTITUTION_DOWN","error_message":"down","request_id":"r"}"""
        val error = PlaidJson.error(json)!!
        assertTrue(error.transient)
        assertFalse(error.reauth)
        // No display_message, so the developer one is used rather than showing a bare code.
        assertEquals("down", error.message)
    }

    @Test
    fun `a successful body is not an error`() {
        assertNull(PlaidJson.error("""{"accounts":[],"request_id":"r"}"""))
        assertNull(PlaidJson.error("not json"))
    }

    @Test
    fun `the institution name is read when Plaid gives one`() {
        assertEquals(
            "USAA",
            PlaidJson.institutionName("""{"institution":{"institution_id":"ins_1","name":"USAA"}}""")
        )
        assertNull(PlaidJson.institutionName("""{"institution":{}}"""))
    }
}
