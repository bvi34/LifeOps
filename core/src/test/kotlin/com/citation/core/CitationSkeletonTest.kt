package com.citation.core

import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.epub.EpubFixtures
import com.citation.core.note.NoteType
import com.citation.core.sync.NotePacket
import com.citation.core.sync.TelemetryPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walking-skeleton acceptance test: EPUB → model → reader position → one highlight → one note
 * with anchor → one packet emitted. If this passes, the pipe is real and every other source is just
 * "add a producer".
 */
class CitationSkeletonTest {

    @Test
    fun epubFlowsAllTheWayToAnEmittedPacket() {
        val skeleton = CitationSkeleton()

        // file → internal model (+ minted key)
        val ingested = skeleton.ingestEpub(EpubFixtures.twoChapterEpub())
        assertNotNull(ingested)
        ingested!!
        assertEquals("ER-Book-1", ingested.book.key.toString())
        assertEquals(2, ingested.book.chapters.size)

        // one highlight → one note captured with its anchor
        val ch0 = ingested.book.chapters[0].text
        val quote = "the clocks were striking thirteen"
        val start = ch0.indexOf(quote)
        assertTrue(start >= 0)
        val note = skeleton.captureNote(
            ingested = ingested,
            chapterOrdinal = 0,
            selectionStart = start,
            selectionEnd = start + quote.length,
            noteBody = "Orwell's famous opening — the tell that something is off.",
            now = 1_700_000_000_000L
        )
        assertEquals(NoteType.PASSAGE_ANCHORED, note.type)
        assertEquals(quote, note.references[0].quotedSnapshot)

        // the anchor re-resolves against the live chapter
        val anchor = note.references[0].anchor as TextAnchor.Flowing
        val res = FuzzyAnchor.resolve(anchor, ch0)
        assertEquals(FuzzyAnchor.Confidence.EXACT, res.confidence)
        assertEquals(quote, ch0.substring(res.start, res.end))

        // telemetry too
        skeleton.reportReading(ingested, minutes = 25, occurredAt = 1_700_000_100_000L)

        // one note packet + one telemetry packet sit in the outbox, self-describing
        val posted = skeleton.outbox.outboxSince(0).map { it.payload }
        assertEquals(2, posted.size)
        val notePacket = posted.filterIsInstance<NotePacket>().single()
        assertEquals(ingested.book.key, notePacket.bookKey)
        assertEquals("9780132350884", notePacket.sourceId)
        assertEquals("The Test Book", notePacket.note.source.title) // legible without the book
        val telemetry = posted.filterIsInstance<TelemetryPacket>().single()
        assertEquals(25, telemetry.minutesRead)
    }

    @Test
    fun keysAreMintedDistinctlyPerType() {
        val skeleton = CitationSkeleton()
        val ingested = skeleton.ingestEpub(EpubFixtures.twoChapterEpub())!!
        val note = skeleton.captureNote(ingested, 0, 0, 4, "x", 1L)
        // Book, Highlight, Note each got their own namespace sequence.
        assertEquals("ER-Book-1", ingested.book.key.toString())
        assertEquals("ER-Note-1", note.key.toString())
    }
}
