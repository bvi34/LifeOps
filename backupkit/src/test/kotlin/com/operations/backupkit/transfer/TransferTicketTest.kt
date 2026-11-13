package com.operations.backupkit.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTicketTest {

    private val ticket = TransferTicket.mint("192.168.1.23", 41234, now = 1_700_000_000_000)

    @Test
    fun `a minted ticket survives its own QR code`() {
        val parsed = TransferTicket.parse(ticket.url())!!
        assertEquals(ticket, parsed)
        assertEquals(1_700_000_000_000 + TransferTicket.DEFAULT_TTL_MS, parsed.expiresAt)
    }

    @Test
    fun `the key rides in the fragment, never in what a browser requests`() {
        val url = ticket.url()
        val beforeFragment = url.substringBefore('#')
        assertEquals(ticket.landingUrl, beforeFragment)
        assertFalse(beforeFragment.contains("k="))
        assertTrue(url.substringAfter('#').startsWith("k="))
    }

    @Test
    fun `toString never prints the key`() {
        val keyText = ticket.url().substringAfter("k=").substringBefore('&')
        assertFalse(ticket.toString().contains(keyText))
    }

    @Test
    fun `ipv6 addresses are bracketed and read back`() {
        val v6 = TransferTicket.mint("fe80::1", 8080, now = 0)
        assertTrue(v6.url().startsWith("http://[fe80::1]:8080/t/"))
        assertEquals(v6, TransferTicket.parse(v6.url()))
    }

    @Test
    fun `anything not exactly our shape is refused`() {
        val good = ticket.url()
        assertNull(TransferTicket.parse(good.replace("http://", "https://")))
        assertNull(TransferTicket.parse(good.substringBefore('#')))
        assertNull(TransferTicket.parse(good.replace("/t/", "/x/")))
        assertNull(TransferTicket.parse(good.replace("#k=", "#k=AA")))
        assertNull(TransferTicket.parse(good.replace("/#", "/extra/#")))
        assertNull(TransferTicket.parse("otpauth://totp/x?secret=ABC"))
        assertNull(TransferTicket.parse(""))
    }

    @Test
    fun `both phones derive the same code, and a different nonce gives a different one`() {
        val nonce = TransferTicket.newNonce()
        val scanned = TransferTicket.parse(ticket.url())!!
        val code = TransferTicket.confirmationCode(ticket.key, nonce)
        assertEquals(code, TransferTicket.confirmationCode(scanned.key, nonce))
        assertTrue(Regex("\\d{3} \\d{3}").matches(code))
        val others = (1..20).map { TransferTicket.confirmationCode(ticket.key, TransferTicket.newNonce()) }.toSet()
        assertTrue(others.size > 15)
    }

    @Test
    fun `a stranger without the key cannot predict the code`() {
        val nonce = TransferTicket.newNonce()
        val stranger = TransferTicket.mint("192.168.1.23", 41234, now = 0)
        assertNotEquals(
            TransferTicket.confirmationCode(ticket.key, nonce),
            TransferTicket.confirmationCode(stranger.key, nonce)
        )
    }

    @Test
    fun `nonce shape`() {
        assertTrue(TransferTicket.isNonce(TransferTicket.newNonce()))
        assertFalse(TransferTicket.isNonce("ABC"))
        assertFalse(TransferTicket.isNonce(null))
        assertFalse(TransferTicket.isNonce("g".repeat(32)))
    }
}
