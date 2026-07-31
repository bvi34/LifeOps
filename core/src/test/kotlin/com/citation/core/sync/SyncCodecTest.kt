package com.citation.core.sync

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.PassageReference
import com.citation.core.note.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCodecTest {

    private val descriptor = SourceDescriptor(
        bookKey = EntityKey("ER", "Book", 3),
        sourceType = SourceType.EPUB,
        sourceId = "9780132350884",
        title = "The Test Book",
        author = "Ada Lovelace"
    )

    @Test
    fun telemetryPacketRoundTrips() {
        val packet = TelemetryPacket(EntityKey("ER", "Book", 3), SourceType.ROYAL_ROAD, "The Test Book", 25, 1_700_000_000_000L)
        val decoded = SyncCodec.decodeUpPacket(SyncCodec.encodeUpPacket(packet))
        assertEquals(packet, decoded)
    }

    @Test
    fun notePacketRoundTripsAndIsLegibleWithoutTheSource() {
        val highlight = Highlight.captureFlowing(
            key = EntityKey("ER", "Highlight", 1),
            source = descriptor,
            chapterText = "It was a bright cold day in April and the clocks were striking thirteen",
            chapterOrdinal = 2,
            selectionStart = 39,
            selectionEnd = 70,
            createdAt = 1000L
        )
        val note = Note.anchored(EntityKey("ER", "Note", 88), "Orwell's tell.", highlight, 2000L)
        val packet = NotePacket.of(note)

        val json = SyncCodec.encodeUpPacket(packet)
        val decoded = SyncCodec.decodeUpPacket(json) as NotePacket

        assertEquals(packet, decoded)
        // Legible without the book: frozen context is right there on the wire.
        assertEquals("9780132350884", decoded.sourceId)
        assertEquals("The Test Book", decoded.note.source.title)
        val anchor = decoded.note.references[0].anchor as TextAnchor.Flowing
        assertEquals(2, anchor.chapterOrdinal)
        assertTrue(decoded.note.references[0].quotedSnapshot.isNotBlank())
    }

    @Test
    fun notePacketWithExternalAnchorRoundTrips() {
        val note = Note.synthesis(
            key = EntityKey("ER", "Note", 5),
            body = "from the O'Reilly copy",
            source = SourceDescriptor(
                bookKey = EntityKey("ER", "Book", 9),
                sourceType = SourceType.OREILLY,
                sourceId = "9781492082279",
                title = "Designing Data-Intensive Applications",
                author = "Kleppmann"
            ),
            references = listOf(
                PassageReference(
                    "quoted from a book we don't hold",
                    TextAnchor.External("epubcfi(/6/14)", "quoted from a book", "9781492082279")
                )
            ),
            createdAt = 1L
        )
        val packet = NotePacket.of(note)
        val decoded = SyncCodec.decodeUpPacket(SyncCodec.encodeUpPacket(packet)) as NotePacket
        assertEquals(packet, decoded)
        val anchor = decoded.note.references[0].anchor as TextAnchor.External
        assertEquals("epubcfi(/6/14)", anchor.location)
    }

    @Test
    fun intentRoundTrips() {
        val intent = AcquireBookIntent(EntityKey("LO", "Book", 42), "Designing Data-Intensive Applications", "Kleppmann")
        assertEquals(intent, SyncCodec.decodeIntent(SyncCodec.encodeIntent(intent)))
    }

    @Test
    fun intentWithNullAuthorRoundTrips() {
        val intent = AcquireBookIntent(EntityKey("LO", "Book", 7), "Some Title", null)
        assertEquals(intent, SyncCodec.decodeIntent(SyncCodec.encodeIntent(intent)))
    }

    @Test
    fun outboundEnvelopeRoundTrips() {
        val env = OutboundEnvelope(
            peer = "ER",
            packets = listOf(
                Mailbox.Versioned(1L, TelemetryPacket(EntityKey("ER", "Book", 1), SourceType.EPUB, "A", 10, 5L)),
                Mailbox.Versioned(2L, TelemetryPacket(null, SourceType.OREILLY, "B", 20, 6L))
            ),
            ackedIntentVersion = 4L
        )
        val decoded = SyncCodec.decodeOutbound(SyncCodec.encodeOutbound(env))
        assertEquals(env, decoded)
    }

    @Test
    fun inboundEnvelopeRoundTrips() {
        val env = InboundEnvelope(
            intents = listOf(
                Mailbox.Versioned(1L, AcquireBookIntent(EntityKey("LO", "Book", 1), "X", "Y")),
                Mailbox.Versioned(2L, AcquireBookIntent(EntityKey("LO", "Book", 2), "Z", null))
            ),
            ackedPacketVersion = 9L
        )
        val decoded = SyncCodec.decodeInbound(SyncCodec.encodeInbound(env))
        assertEquals(env, decoded)
    }
}
