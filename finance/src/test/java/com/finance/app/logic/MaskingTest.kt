package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a full account number out of the database, and therefore out of a backup.
 */
class MaskingTest {

    @Test
    fun `only the last four survive, whatever shape the provider sent`() {
        assertEquals("6789", Masking.truncate("000123456789"))
        assertEquals("3456", Masking.truncate("1234-5678-9012-3456"))
        assertEquals("3456", Masking.truncate("••••3456"))
        assertEquals("0000", Masking.truncate("0000"))
    }

    @Test
    fun `fewer than four digits is refused rather than padded`() {
        // "0012" would be a number this app invented. The honest answer is that we don't have it.
        assertNull(Masking.truncate("12"))
        assertNull(Masking.truncate(""))
        assertNull(Masking.truncate(null))
        assertNull(Masking.truncate("no digits here"))
    }

    @Test
    fun `rendering says there was more without pretending to a length`() {
        assertEquals("••3456", Masking.render("1234-5678-9012-3456"))
        assertNull(Masking.render("12"))
        assertEquals("Classic Checking ••3456", Masking.label("Classic Checking", "3456"))
        assertEquals("Classic Checking", Masking.label("Classic Checking", null))
    }

    @Test
    fun `the full-number check trips on real numbers and not on masks or years`() {
        assertTrue(Masking.looksLikeFullNumber("000123456789"))
        assertTrue(Masking.looksLikeFullNumber("314074269"))
        assertFalse(Masking.looksLikeFullNumber("3456"))
        assertFalse(Masking.looksLikeFullNumber("2024"))
        assertFalse(Masking.looksLikeFullNumber("SAFEWAY 1042"))
        assertFalse(Masking.looksLikeFullNumber(null))
    }
}
