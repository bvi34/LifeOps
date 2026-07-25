package com.citation.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxTest {

    @Test
    fun postsAssignMonotonicVersions() {
        val mb = Mailbox<String, String>()
        assertEquals(1L, mb.post("a").version)
        assertEquals(2L, mb.post("b").version)
        assertEquals(2L, mb.currentOutVersion)
    }

    @Test
    fun outboxSinceReturnsOnlyNewer() {
        val mb = Mailbox<String, String>()
        mb.post("a"); mb.post("b"); mb.post("c")
        val since1 = mb.outboxSince(1)
        assertEquals(listOf("b", "c"), since1.map { it.payload })
    }

    @Test
    fun ackDropsAcknowledged() {
        val mb = Mailbox<String, String>()
        mb.post("a"); mb.post("b"); mb.post("c")
        mb.ackOutbox(2)
        assertEquals(listOf("c"), mb.outboxSince(0).map { it.payload })
    }

    @Test
    fun deliverIsIdempotentByVersion() {
        val mb = Mailbox<String, String>()
        val items = listOf(Mailbox.Versioned(1L, "x"), Mailbox.Versioned(2L, "y"))
        mb.deliver(items)
        mb.deliver(items) // re-delivery — must not duplicate
        assertEquals(listOf("x", "y"), mb.pending().map { it.payload })
    }

    @Test
    fun consumeAdvancesCursorAndClears() {
        val mb = Mailbox<String, String>()
        mb.deliver(listOf(Mailbox.Versioned(1L, "x"), Mailbox.Versioned(2L, "y")))
        mb.consumeThrough(1)
        assertEquals(1L, mb.inboxCursor)
        assertEquals(listOf("y"), mb.pending().map { it.payload })
        // A re-delivery of an already-consumed version is ignored.
        mb.deliver(listOf(Mailbox.Versioned(1L, "x")))
        assertEquals(listOf("y"), mb.pending().map { it.payload })
    }

    @Test
    fun restoreResumesVersioningWithoutReuse() {
        val mb = Mailbox<String, String>()
        mb.restore(outVersion = 40, inboxCursor = 10)
        assertEquals(41L, mb.post("next").version)
        assertTrue(mb.inboxCursor == 10L)
    }
}
