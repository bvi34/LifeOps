package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allow-list is the mechanism behind the manifest's promise that this app talks to two hosts and
 * nothing else, so it is tested as a security control rather than as a string comparison.
 */
class EndpointsTest {

    @Test
    fun `the two providers and the login page are reachable`() {
        assertTrue(Endpoints.permits("${Endpoints.PLAID_PRODUCTION}/accounts/balance/get"))
        assertTrue(Endpoints.permits("${Endpoints.PLAID_SANDBOX}/link/token/create"))
        assertTrue(Endpoints.permits("${Endpoints.MERCURY_BASE}/accounts"))
        assertTrue(Endpoints.permits("https://link.plaid.com/?token=link-production-abc"))
    }

    @Test
    fun `nothing else is`() {
        assertFalse(Endpoints.permits("https://example.com/whatever"))
        assertFalse(Endpoints.permits("https://api.mercury.com.evil.example/accounts"))
        assertFalse(Endpoints.permits("https://notplaid.com/accounts"))
        assertFalse(Endpoints.permits(""))
    }

    @Test
    fun `a suffix check would let a lookalike host through, so the match is exact`() {
        // "plaid.com.attacker.net" ends in neither of these, but "production.plaid.com.attacker.net"
        // would pass a naive endsWith and is precisely the attack an exact match has to survive.
        assertFalse(Endpoints.permits("https://production.plaid.com.attacker.net/accounts"))
        assertFalse(Endpoints.permits("https://evil-production.plaid.com/accounts"))
    }

    @Test
    fun `userinfo in the authority does not smuggle a host past the check`() {
        // The real host here is `evil.example`; the part before the @ is a username.
        assertFalse(Endpoints.permits("https://api.mercury.com@evil.example/accounts"))
        assertFalse(Endpoints.permits("https://production.plaid.com:pass@evil.example/"))
    }

    @Test
    fun `plaintext is refused even to a host that would otherwise be fine`() {
        // These requests carry a token that can read a bank account.
        assertFalse(Endpoints.permits("http://production.plaid.com/accounts/balance/get"))
        assertFalse(Endpoints.permits("http://api.mercury.com/api/v1/accounts"))
    }

    @Test
    fun `dollars become cents without losing one to floating point`() {
        // (5.4 * 100).toLong() is 539 on any IEEE-754 machine, and a cent lost on every parse is a
        // balance that never reconciles against the bank's own app.
        assertEquals(540L, Endpoints.dollarsToCents(5.4))
        assertEquals(1_549L, Endpoints.dollarsToCents(15.49))
        assertEquals(-4_000L, Endpoints.dollarsToCents(-40.0))
        assertEquals(0L, Endpoints.dollarsToCents(0.0))
        assertEquals(123_456_789L, Endpoints.dollarsToCents(1_234_567.89))
    }

    @Test
    fun `a missing or nonsense amount is absent rather than zero`() {
        assertNull(Endpoints.dollarsToCents(null))
        assertNull(Endpoints.dollarsToCents(Double.NaN))
        assertNull(Endpoints.dollarsToCents(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `environments resolve by key and default to the harmless one`() {
        assertEquals(Endpoints.PlaidEnvironment.PRODUCTION, Endpoints.PlaidEnvironment.fromKey("production"))
        // Sending sandbox credentials to production fails confusingly; the other way round fails
        // safely, so an unrecognised value lands on sandbox.
        assertEquals(Endpoints.PlaidEnvironment.SANDBOX, Endpoints.PlaidEnvironment.fromKey("development"))
        assertEquals(Endpoints.PlaidEnvironment.SANDBOX, Endpoints.PlaidEnvironment.fromKey(null))
    }
}
