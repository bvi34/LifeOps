package com.citation.core.sync

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineTest {

    private fun telemetry(title: String) =
        TelemetryPacket(EntityKey("ER", "Book", 1), SourceType.EPUB, title, 10, 1L)

    private fun intent(v: Long, title: String) =
        Mailbox.Versioned(v, AcquireBookIntent(EntityKey("LO", "Book", v), title, null))

    @Test
    fun outboundCarriesUnackedPacketsAndIntentCursor() {
        val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
        mailbox.post(telemetry("A"))
        mailbox.post(telemetry("B"))
        val engine = SyncEngine(mailbox)

        val out = engine.buildOutbound()
        assertEquals("ER", out.peer)
        assertEquals(listOf("A", "B"), out.packets.map { (it.payload as TelemetryPacket).title })
        assertEquals(0L, out.ackedIntentVersion)
    }

    @Test
    fun applyInboundPrunesOutboxDeliversIntentsAndAdvancesCursor() {
        val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
        mailbox.post(telemetry("A")) // v1
        mailbox.post(telemetry("B")) // v2
        val engine = SyncEngine(mailbox)

        val reconciled = ArrayList<String>()
        val inbound = InboundEnvelope(
            intents = listOf(intent(1, "Get X"), intent(2, "Get Y")),
            ackedPacketVersion = 1 // LifeOps stored packet v1
        )
        val processed = engine.applyInbound(inbound) { reconciled.add(it.title) }

        // Packet v1 acked/pruned; v2 still pending to send.
        assertEquals(listOf("B"), mailbox.outboxSince(0).map { (it.payload as TelemetryPacket).title })
        // Both intents reconciled and consumed; cursor advanced to 2.
        assertEquals(listOf("Get X", "Get Y"), reconciled)
        assertEquals(listOf("Get X", "Get Y"), processed.map { it.title })
        assertEquals(2L, mailbox.inboxCursor)
    }

    @Test
    fun reapplyingTheSameInboundIsIdempotent() {
        val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
        val engine = SyncEngine(mailbox)
        val inbound = InboundEnvelope(listOf(intent(1, "Get X")), ackedPacketVersion = 0)

        val first = ArrayList<String>()
        engine.applyInbound(inbound) { first.add(it.title) }
        val second = ArrayList<String>()
        engine.applyInbound(inbound) { second.add(it.title) }

        assertEquals(listOf("Get X"), first)
        assertEquals(emptyList<String>(), second) // already consumed — no reprocessing
        assertEquals(1L, mailbox.inboxCursor)
    }
}
