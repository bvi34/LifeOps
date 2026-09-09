package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRefTest {

    @Test
    fun `a ref formats and parses back to itself`() {
        val ref = SecretRef("finance", "usaa", "access-token")

        assertEquals("finance/usaa/access-token", ref.format())
        assertEquals(ref, SecretRef.parse(ref.format()))
    }

    @Test
    fun `anything that is not three good segments is refused`() {
        assertNull(SecretRef.parse("finance/usaa"))
        assertNull(SecretRef.parse("finance/usaa/access/token"))
        assertNull(SecretRef.parse("Finance/usaa/token"))
        assertNull(SecretRef.parse("finance//token"))
        assertNull(SecretRef.parse(""))
        assertNull(SecretRef.parse("finance/us aa/token"))
    }

    @Test
    fun `constructing an invalid ref throws where it is written rather than where it is read`() {
        val thrown = runCatching { SecretRef("finance", "USAA", "token") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `segment turns real-world ids into filing names`() {
        assertEquals("my-bank", SecretRef.segment("My Bank"))
        assertEquals("conn.7f3a", SecretRef.segment("conn.7F3A"))
        assertEquals("calibre-web", SecretRef.segment("  Calibre/Web  "))
        assertEquals("unnamed", SecretRef.segment("   "))
        // Non-ASCII letters are not letters as far as an address is concerned; `isLetterOrDigit`
        // would have kept these and produced a segment that [SecretRef.valid] then rejected.
        assertEquals("caf", SecretRef.segment("Café"))
        assertTrue(SecretRef.valid(SecretRef.segment("Café ☕")))
    }

    @Test
    fun `every segment produced by segment is a legal segment`() {
        val awkward = listOf("", "///", "ÜBER", "a b c", "🔒", "..", "-", "9")

        awkward.forEach { raw ->
            val segment = SecretRef.segment(raw)
            assertTrue("'$raw' produced '$segment'", SecretRef.valid(segment))
        }
    }

    @Test
    fun `an app-wide secret uses the self connection`() {
        val ref = SecretRef("finance", SecretRef.SELF, "plaid-client-secret")

        assertEquals("finance/self/plaid-client-secret", ref.format())
    }
}
