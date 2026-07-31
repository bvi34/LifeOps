package com.citation.core.sync

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileEnvelopeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun outboundAndInboundExchangeThroughFiles() {
        val store = FileEnvelopeStore(tmp.newFolder("mailbox"))

        // Nothing from LifeOps yet.
        assertNull(store.readInbound())

        // Citation writes up; LifeOps reads it (loopback via the same store).
        val outbound = OutboundEnvelope(
            peer = "ER",
            packets = listOf(Mailbox.Versioned(1L, TelemetryPacket(EntityKey("ER", "Book", 1), SourceType.EPUB, "A", 12, 3L))),
            ackedIntentVersion = 0
        )
        store.writeOutbound(outbound)
        assertEquals(outbound, store.readOutbound())

        // LifeOps responds down; Citation reads it.
        val inbound = InboundEnvelope(
            intents = listOf(Mailbox.Versioned(1L, AcquireBookIntent(EntityKey("LO", "Book", 5), "Get X", "Author"))),
            ackedPacketVersion = 1
        )
        store.writeInbound(inbound)
        assertEquals(inbound, store.readInbound())
    }

    @Test
    fun fullRoundThroughEngineAndFiles() {
        val store = FileEnvelopeStore(tmp.newFolder("mailbox"))
        val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
        mailbox.post(TelemetryPacket(EntityKey("ER", "Book", 1), SourceType.EPUB, "Read", 30, 1L))
        val engine = SyncEngine(mailbox)

        // 1) Citation builds + writes outbound.
        store.writeOutbound(engine.buildOutbound())

        // 2) LifeOps (simulated) reads it, acks the packet, and replies with an intent.
        val received = store.readOutbound()!!
        assertEquals("Read", (received.packets.single().payload as TelemetryPacket).title)
        store.writeInbound(
            InboundEnvelope(
                intents = listOf(Mailbox.Versioned(1L, AcquireBookIntent(EntityKey("LO", "Book", 9), "Buy Y", null))),
                ackedPacketVersion = received.packets.maxOf { it.version }
            )
        )

        // 3) Citation applies the response: outbox drained, intent reconciled.
        val reconciled = ArrayList<String>()
        engine.applyInbound(store.readInbound()!!) { reconciled.add(it.title) }

        assertEquals(emptyList<Mailbox.Versioned<UpPacket>>(), mailbox.outboxSince(0))
        assertEquals(listOf("Buy Y"), reconciled)
    }
}
