package com.operations.backupkit.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What the settings screen can say about a credential without spending a request on it — and, more
 * importantly, what it must *not* conclude. An unparseable expiry is the case that matters: reading
 * it as "expired" would switch off a household's backups over a date format.
 */
class AzureSasTest {

    private val expiry = Instant.parse("2026-12-31T23:59:59Z").toEpochMilli()
    private val sas = "sv=2022-11-02&ss=b&srt=co&sp=rwdlac&se=2026-12-31T23:59:59Z&sig=abc%2Fdef"

    @Test
    fun `a parameter is read back decoded`() {
        assertEquals("abc/def", AzureSas.param(sas, "sig"))
        assertEquals("rwdlac", AzureSas.param(sas, "sp"))
        assertNull(AzureSas.param(sas, "srk"))
        assertNull(AzureSas.param("", "sig"))
    }

    @Test
    fun `every shape azure writes an expiry in is the same instant`() {
        assertEquals(expiry, AzureSas.expiresAt(sas))
        assertEquals(
            Instant.parse("2026-12-31T23:59:00Z").toEpochMilli(),
            AzureSas.expiresAt("se=2026-12-31T23:59Z&sig=x")
        )
        assertEquals(
            Instant.parse("2026-12-31T00:00:00Z").toEpochMilli(),
            AzureSas.expiresAt("se=2026-12-31&sig=x")
        )
        assertEquals(
            Instant.parse("2026-12-31T22:59:59Z").toEpochMilli(),
            AzureSas.expiresAt("se=2026-12-31T23%3A59%3A59%2B01%3A00&sig=x")
        )
    }

    @Test
    fun `a token with no readable expiry is never treated as expired`() {
        assertNull(AzureSas.expiresAt("sp=rw&sig=x"))
        assertFalse(AzureSas.isExpired("sp=rw&sig=x", now = Long.MAX_VALUE))
        assertNull(AzureSas.expiresAt("se=whenever&sig=x"))
        assertFalse(AzureSas.isExpired("se=whenever&sig=x", now = Long.MAX_VALUE))
    }

    @Test
    fun `expiry is read off the token itself`() {
        assertFalse(AzureSas.isExpired(sas, now = expiry - 1))
        assertTrue(AzureSas.isExpired(sas, now = expiry))
        assertTrue(AzureSas.isExpired(sas, now = expiry + 1))
    }

    @Test
    fun `permissions decide what the app may attempt`() {
        assertTrue(AzureSas.canWrite("sp=c&sig=x"))
        assertTrue(AzureSas.canWrite("sp=w&sig=x"))
        assertFalse(AzureSas.canWrite("sp=rl&sig=x"))
        assertTrue(AzureSas.canList("sp=rl&sig=x"))
        assertFalse(AzureSas.canList("sp=rw&sig=x"))
        assertTrue(AzureSas.canDelete("sp=rwd&sig=x"))
        assertFalse(AzureSas.canDelete("sp=rwl&sig=x"))
        assertEquals("", AzureSas.permissions("sig=x"))
    }

    @Test
    fun `the description says what it can do and how long for`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(
            "Signature allows upload, list, delete — expires in 2 days.",
            AzureSas.describe(sas, now = expiry - 2 * day)
        )
        assertEquals(
            "Signature allows upload — expired.",
            AzureSas.describe("sp=c&se=2026-12-31T23:59:59Z&sig=x", now = expiry + day)
        )
        assertEquals(
            "Signature allows upload — no expiry recorded in the token.",
            AzureSas.describe("sp=cw&sig=x", now = expiry)
        )
        assertEquals("No signature saved.", AzureSas.describe("  ", now = expiry))
    }
}
