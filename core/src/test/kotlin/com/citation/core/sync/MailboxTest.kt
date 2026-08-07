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
    fun seedOutboxRebuildsUnackedItemsAtTheirVersions() {
        // Simulate a restart: counters restored, but the in-memory outbox is empty.
        val mb = Mailbox<String, String>()
        mb.restore(outVersion = 3, inboxCursor = 0)
        mb.seedOutbox(listOf(Mailbox.Versioned(2L, "b"), Mailbox.Versioned(3L, "c")))

        // The seeded items are resendable, ordered by version…
        assertEquals(listOf("b", "c"), mb.outboxSince(0).map { it.payload })
        // …and a fresh post continues above the restored counter without reusing a version.
        assertEquals(4L, mb.post("d").version)
    }

    @Test
    fun seedOutboxDedupesByVersion() {
        val mb = Mailbox<String, String>()
        mb.post("a") // version 1, already in the outbox this session
        mb.seedOutbox(listOf(Mailbox.Versioned(1L, "a"), Mailbox.Versioned(2L, "b")))
        assertEquals(listOf("a", "b"), mb.outboxSince(0).map { it.payload })
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
