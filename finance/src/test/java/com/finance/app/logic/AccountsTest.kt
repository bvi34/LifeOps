package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign rule, tested from the direction it actually goes wrong in.
 *
 * The bug this guards against is not subtle and is not rare: it is adding a credit card balance to a
 * checking balance and reporting the household as richer for owing money. Every assertion here about
 * a card is really that assertion.
 */
class AccountsTest {

    @Test
    fun `a card's balance is stored as the institution reports it - positive means you owe`() {
        val card = account(kind = AccountKind.CREDIT, current = 840.0, limit = 5000.0)
        assertEquals(84_000L, card.balance.currentCents)
    }

    @Test
    fun `owing money makes you poorer, not richer`() {
        val position = Accounts.netPosition(
            listOf(
                account(id = "chk", kind = AccountKind.DEPOSITORY, current = 2_400.0, available = 2_400.0),
                account(id = "visa", kind = AccountKind.CREDIT, current = 840.0)
            )
        )
        assertEquals(240_000L, position.assetsCents)
        assertEquals(84_000L, position.liabilitiesCents)
        assertEquals(156_000L, position.netCents)
    }

    @Test
    fun `a card in credit is an asset, not a negative debt`() {
        // Overpay a card and the institution reports a negative balance. Adding that to the debt
        // pile as a negative would understate both sides of the picture at once.
        val position = Accounts.netPosition(
            listOf(account(kind = AccountKind.CREDIT, current = -60.0))
        )
        assertEquals(6_000L, position.assetsCents)
        assertEquals(0L, position.liabilitiesCents)
    }

    @Test
    fun `cash is depository only - investments are assets but are not cash`() {
        val position = Accounts.netPosition(
            listOf(
                account(id = "chk", kind = AccountKind.DEPOSITORY, current = 1_000.0, available = 1_000.0),
                account(id = "ira", kind = AccountKind.INVESTMENT, current = 40_000.0)
            )
        )
        assertEquals(4_100_000L, position.assetsCents)
        assertEquals(100_000L, position.cashCents)
    }

    @Test
    fun `the picture plans with available, because a hold is money that will not be there`() {
        // $2,400 on the ledger, $600 of it held against a hotel deposit.
        val checking = account(kind = AccountKind.DEPOSITORY, current = 2_400.0, available = 1_800.0)
        assertEquals(180_000L, checking.balance.spendable(AccountKind.DEPOSITORY))
        assertEquals(180_000L, Accounts.netPosition(listOf(checking)).cashCents)
    }

    @Test
    fun `a card plans with its balance - its available is headroom to borrow, not money`() {
        val card = account(kind = AccountKind.CREDIT, current = 840.0, available = 4_160.0, limit = 5_000.0)
        assertEquals(84_000L, card.balance.spendable(AccountKind.CREDIT))
    }

    @Test
    fun `closed and excluded accounts are left out of the picture`() {
        val position = Accounts.netPosition(
            listOf(
                account(id = "a", current = 100.0, available = 100.0),
                account(id = "shut", kind = AccountKind.CREDIT, current = 500.0, closed = true),
                account(id = "hidden", current = 900.0, available = 900.0, included = false)
            )
        )
        assertEquals(10_000L, position.assetsCents)
        assertEquals(0L, position.liabilitiesCents)
    }

    @Test
    fun `utilisation is a fraction of the limit, and absent when there is no limit`() {
        assertEquals(0.2, account(kind = AccountKind.CREDIT, current = 1_000.0, limit = 5_000.0).balance.utilisation()!!, 1e-9)
        assertNull(account(kind = AccountKind.DEPOSITORY, current = 1_000.0).balance.utilisation())
        // A limit of zero is not a limit; dividing by it would be an infinity on a screen.
        assertNull(account(kind = AccountKind.CREDIT, current = 10.0, limit = 0.0).balance.utilisation())
    }

    @Test
    fun `provider types map to kinds, and an unknown one is an asset rather than a guess`() {
        assertEquals(AccountKind.DEPOSITORY, AccountKind.fromProviderType("depository", "checking"))
        assertEquals(AccountKind.CREDIT, AccountKind.fromProviderType("credit", "credit card"))
        assertEquals(AccountKind.LOAN, AccountKind.fromProviderType("loan", "mortgage"))
        assertEquals(AccountKind.INVESTMENT, AccountKind.fromProviderType("investment", "ira"))
        // The expensive mistake would be guessing that an unknown type is a debt: it would silently
        // reduce the household's net worth by a number nobody could trace back to anything.
        val unknown = AccountKind.fromProviderType("crypto-vault", "cold-storage")
        assertEquals(AccountKind.OTHER, unknown)
        assertTrue(!unknown.owed)
    }

    @Test
    fun `kinds resolve by key, and an unrecognised key does not throw`() {
        assertEquals(AccountKind.CREDIT, AccountKind.fromKey("credit"))
        assertEquals(AccountKind.OTHER, AccountKind.fromKey("something-from-a-later-version"))
        assertEquals(AccountKind.OTHER, AccountKind.fromKey(null))
    }

    @Test
    fun `funding accounts are cash accounts, richest first`() {
        val funding = Accounts.fundingAccounts(
            listOf(
                account(id = "small", current = 100.0, available = 100.0),
                account(id = "card", kind = AccountKind.CREDIT, current = 50.0),
                account(id = "big", current = 900.0, available = 900.0)
            )
        )
        assertEquals(listOf("big", "small"), funding.map { it.id })
    }
}
