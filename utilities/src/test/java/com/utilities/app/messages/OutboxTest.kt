package com.utilities.app.messages

import com.utilities.app.messages.logic.Addresses
import com.utilities.app.messages.logic.ChatMessage
import com.utilities.app.messages.logic.Outbox
import com.utilities.app.messages.logic.OutboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge over the seconds — sometimes hours — between this app sending a text and the
 * platform's store having it.
 *
 * The rule being tested is "the store now has this message", and the cost of getting it wrong is
 * visible to anybody: too strict and every reply is shown twice, for a day; too loose and a second
 * message with the same words silently disappears. Both halves are asserted.
 */
class OutboxTest {

    private val now = 1_700_000_000_000L

    private fun stored(body: String, at: Long, address: String = "5550109999", outgoing: Boolean = true) =
        ChatMessage(id = at, threadId = 7L, address = address, body = body, at = at, outgoing = outgoing)

    private fun echo(body: String, at: Long, address: String = "+1 (555) 010-9999", failed: Boolean = false) =
        OutboxEntry(address = address, body = body, at = at, failed = failed)

    @Test
    fun `an echo the store does not have is shown, as pending`() {
        val merged = Outbox.merge(emptyList(), listOf(echo("on my way", now)), threadId = 7L)
        assertEquals(1, merged.size)
        assertTrue(merged.single().pending)
        assertTrue(merged.single().outgoing)
        assertEquals(7L, merged.single().threadId)
    }

    @Test
    fun `an echo the store has caught up with is shown once, not twice`() {
        val real = stored("on my way", now + 3_000)
        val merged = Outbox.merge(listOf(real), listOf(echo("on my way", now)), threadId = 7L)
        assertEquals(listOf(real), merged)
    }

    @Test
    fun `the same number written three ways is the same number`() {
        val real = stored("hello", now, address = "+15550109999")
        assertTrue(Outbox.matches(echo("hello", now, address = "555-010-9999"), real))
        assertTrue(Outbox.matches(echo("hello", now, address = "(555) 010-9999"), real))
    }

    @Test
    fun `whitespace is not a different message`() {
        val real = stored("see you  then", now)
        assertTrue(Outbox.matches(echo("see you then", now), real))
        assertTrue(Outbox.matches(echo(" see you then ", now), real))
    }

    @Test
    fun `the same words deliberately sent twice are two messages`() {
        // The other failure mode. A gap wider than the settle window means a second message.
        val real = stored("ok", now)
        assertFalse(Outbox.matches(echo("ok", now + Outbox.SETTLE_WINDOW_MS + 1), real))
    }

    @Test
    fun `an incoming message never settles an echo`() {
        val theirs = stored("on my way", now, outgoing = false)
        assertFalse(Outbox.matches(echo("on my way", now), theirs))
    }

    @Test
    fun `a failed echo is kept and shown as failed`() {
        // The one thing worse than a reply that disappears is one that disappears after failing.
        val merged = Outbox.merge(emptyList(), listOf(echo("nope", now, failed = true)), threadId = 7L)
        assertTrue(merged.single().failed)
        assertFalse(merged.single().pending)
    }

    @Test
    fun `merged messages are in time order and echoes have ids of their own`() {
        val merged = Outbox.merge(
            stored = listOf(stored("first", now - 10_000), stored("second", now - 5_000)),
            echoes = listOf(echo("third", now)),
            threadId = 7L
        )
        assertEquals(listOf("first", "second", "third"), merged.map { it.body })
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
        assertTrue("echo ids must not collide with the store's", merged.last().id < 0)
    }

    @Test
    fun `settled says which echoes to delete`() {
        val landed = echo("landed", now)
        val waiting = echo("waiting", now)
        val done = Outbox.settled(listOf(stored("landed", now)), listOf(landed, waiting))
        assertEquals(listOf(landed), done)
    }

    @Test
    fun `an echo older than a day is dropped whether or not it ever landed`() {
        val old = echo("ancient", now - Outbox.EXPIRY_MS - 1)
        val recent = echo("recent", now - 1_000)
        assertEquals(listOf(recent), Outbox.live(listOf(old, recent), now))
    }
}

/** Comparing and printing phone numbers, which is the quietly hard part of a messaging app. */
class AddressesTest {

    @Test
    fun `the same subscriber written any way compares equal`() {
        listOf("+1 (555) 010-9999", "5550109999", "555-010-9999", "1-555-010-9999")
            .forEach { assertEquals("5550109999", Addresses.key(it)) }
        assertTrue(Addresses.same("+15550109999", "(555) 010-9999"))
    }

    @Test
    fun `two different short codes are two different senders`() {
        // Shorter than the significant length, so they compare whole — there is no country code to
        // discard and a prefix rule would merge a bank with a delivery company.
        assertFalse(Addresses.same("22395", "22396"))
        assertEquals("22395", Addresses.key("22395"))
    }

    @Test
    fun `a sender with a name rather than a number keeps it`() {
        assertEquals("verizon", Addresses.key("VERIZON"))
        assertEquals("VERIZON", Addresses.display("VERIZON"))
    }

    @Test
    fun `only the grouping that is not a guess is applied`() {
        assertEquals("(555) 010-9999", Addresses.display("5550109999"))
        assertEquals("(555) 010-9999", Addresses.display("15550109999"))
        // An international number is shown as it arrived: its grouping is not ours to invent.
        assertEquals("+44 20 7946 0000", Addresses.display("+44 20 7946 0000"))
    }
}
