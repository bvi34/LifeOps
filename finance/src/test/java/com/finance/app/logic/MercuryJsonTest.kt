package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MercuryJsonTest {

    private val chicago: ZoneId = ZoneId.of("America/Chicago")

    @Test
    fun `accounts parse, and the full account number does not survive the parser`() {
        val json = """
            {
              "accounts": [
                {
                  "id": "acc-uuid-1",
                  "name": "Mercury Checking",
                  "nickname": "Operating",
                  "accountNumber": "204512339087",
                  "routingNumber": "084106768",
                  "kind": "checking",
                  "status": "active",
                  "currentBalance": 18422.19,
                  "availableBalance": 18100.0
                }
              ]
            }
        """.trimIndent()

        val account = MercuryJson.accounts(json, connectionId = "merc").single()
        assertEquals("merc:acc-uuid-1", account.id)
        // The nickname is what the person calls it, so it wins over the product name.
        assertEquals("Operating", account.name)
        assertEquals("Mercury Checking", account.officialName)
        assertEquals(AccountKind.DEPOSITORY, account.kind)
        assertEquals(1_842_219L, account.balance.currentCents)
        assertEquals(1_810_000L, account.balance.availableCents)

        // Twelve digits went in; four came out. There is nowhere for the rest to go, and the routing
        // number is not a field on an account at all.
        assertEquals("9087", account.mask)
        assertTrue(Masking.looksLikeFullNumber("204512339087"))
        assertEquals("Operating ••9087", account.displayName())
    }

    @Test
    fun `an archived account is closed and stops counting towards the picture`() {
        val json = """
            {"accounts":[{"id":"a","name":"Old","accountNumber":"1234","kind":"checking",
              "status":"archived","currentBalance":0.0}]}
        """.trimIndent()
        val account = MercuryJson.accounts(json, "merc").single()
        assertTrue(account.closed)
        assertEquals(0L, Accounts.netPosition(listOf(account)).assetsCents)
    }

    @Test
    fun `Mercury's charge card is a credit account`() {
        val json = """
            {"accounts":[{"id":"c","name":"Mercury IO","accountNumber":"5555","kind":"creditCard",
              "status":"active","currentBalance":4210.0}]}
        """.trimIndent()
        assertEquals(AccountKind.CREDIT, MercuryJson.accounts(json, "merc").single().kind)
    }

    @Test
    fun `there is no sign flip, because Mercury already reads like a statement`() {
        val json = """
            {
              "total": 2,
              "transactions": [
                {"id":"t-1","amount":-249.0,"bankDescription":"AWS EDU","counterpartyName":"Amazon Web Services",
                 "kind":"card","status":"sent","createdAt":"2026-09-03T14:00:00Z","postedAt":"2026-09-04T09:00:00Z"},
                {"id":"t-2","amount":9800.0,"bankDescription":"CLIENT WIRE","counterpartyName":"Northwind Ltd",
                 "kind":"incomingDomesticWire","status":"sent","createdAt":"2026-09-05T11:00:00Z","postedAt":"2026-09-05T11:30:00Z"}
              ]
            }
        """.trimIndent()

        val rows = MercuryJson.transactions(json, accountId = "merc:acc", zone = chicago)
        val out = rows.first { it.providerTransactionId == "t-1" }
        val inn = rows.first { it.providerTransactionId == "t-2" }
        assertEquals(-24_900L, out.amountCents)
        assertTrue(out.outflow)
        assertEquals(980_000L, inn.amountCents)
        assertTrue(inn.inflow)
        assertEquals("Amazon Web Services", out.merchant)
    }

    @Test
    fun `a payment made in the evening is filed on the day the person remembers making it`() {
        // 8pm Central is the 5th to whoever made it and the 6th in UTC.
        val json = """
            {"transactions":[{"id":"t","amount":-50.0,"bankDescription":"X","kind":"card",
              "status":"sent","createdAt":"2026-09-06T01:22:03Z","postedAt":"2026-09-06T01:22:03Z"}]}
        """.trimIndent()
        val row = MercuryJson.transactions(json, "merc:acc", chicago).single()
        assertEquals(LocalDate.parse("2026-09-05"), row.date)
    }

    @Test
    fun `a transaction that has not posted is pending`() {
        val json = """
            {"transactions":[{"id":"t","amount":-50.0,"bankDescription":"X","kind":"externalTransfer",
              "status":"pending","createdAt":"2026-09-06T14:00:00Z","postedAt":null}]}
        """.trimIndent()
        val row = MercuryJson.transactions(json, "merc:acc", chicago).single()
        assertTrue(row.pending)
        assertEquals(LocalDate.parse("2026-09-06"), row.date)
    }

    @Test
    fun `a failed transaction is money that never moved`() {
        // Mercury keeps the row so you can see what happened. Counting it would take money out of a
        // balance that still has it.
        val json = """
            {"transactions":[{"id":"t","amount":-50.0,"bankDescription":"X","kind":"externalTransfer",
              "status":"failed","createdAt":"2026-09-06T14:00:00Z","postedAt":null,
              "failedAt":"2026-09-06T15:00:00Z","reasonForFailure":"insufficient funds"}]}
        """.trimIndent()
        assertTrue(MercuryJson.transactions(json, "merc:acc", chicago).isEmpty())
    }

    @Test
    fun `internal transfers are marked as transfers and stay out of the roll-ups`() {
        val json = """
            {"transactions":[{"id":"t","amount":-2000.0,"bankDescription":"To savings",
              "kind":"internalTransfer","status":"sent","createdAt":"2026-09-02T14:00:00Z",
              "postedAt":"2026-09-02T14:00:00Z"}]}
        """.trimIndent()
        val row = MercuryJson.transactions(json, "merc:acc", chicago).single()
        assertTrue(row.transfer)
        assertEquals(Category.TRANSFER, row.category)
        assertEquals(
            0L,
            CashFlow.summarise(listOf(row), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")).outCents
        )
    }

    @Test
    fun `garbage is an empty answer, not an exception out of a parser`() {
        assertTrue(MercuryJson.accounts("<html>403</html>", "merc").isEmpty())
        assertTrue(MercuryJson.accounts("{}", "merc").isEmpty())
        assertTrue(MercuryJson.transactions("[1,2,3]", "merc:acc").isEmpty())
    }

    @Test
    fun `a row with no id or no amount is dropped rather than half-built`() {
        val noId = """{"transactions":[{"amount":-1.0,"postedAt":"2026-09-02T14:00:00Z"}]}"""
        val noAmount = """{"transactions":[{"id":"t","postedAt":"2026-09-02T14:00:00Z"}]}"""
        assertTrue(MercuryJson.transactions(noId, "merc:acc", chicago).isEmpty())
        assertTrue(MercuryJson.transactions(noAmount, "merc:acc", chicago).isEmpty())
    }

    @Test
    fun `errors are read out of whichever shape Mercury used`() {
        assertEquals("Invalid token", MercuryJson.error("""{"errors":{"message":"Invalid token"}}"""))
        assertEquals("Forbidden", MercuryJson.error("""{"error":"Forbidden"}"""))
        assertNull(MercuryJson.error("""{"accounts":[]}"""))
        assertNull(MercuryJson.error("not json"))
    }
}
