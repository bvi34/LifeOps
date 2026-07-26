package com.citation.core.capture

import com.citation.core.anchor.TextAnchor
import com.citation.core.identity.IdentityKey
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBuilderTest {

    private val now = 1_690_000_000_000L
    private val hk = EntityKey("ER", "Highlight", 1)
    private val nk = EntityKey("ER", "Note", 1)

    @Test
    fun descriptorFreezesProvenanceAsAnUnboundCaptureSource() {
        val prov = ProvenanceLadder.resolve(
            RawCapture("clip", url = "https://x.example/p", title = "A Page", capturedAt = now)
        )
        val d = CaptureBuilder.descriptor(prov)
        assertNull("provisional until promotion", d.bookKey)
        assertEquals(SourceType.CAPTURE, d.sourceType)
        assertEquals("url:https://x.example/p", d.sourceId)
        assertEquals("A Page", d.title)
    }

    @Test
    fun quotedCaptureBuildsAnExternalAnchoredPassageNote() {
        val prov = ProvenanceLadder.resolve(
            RawCapture("q", bookIdentity = IdentityKey.Isbn("9781449373320"), title = "DDIA", capturedAt = now)
        )
        val highlight = CaptureBuilder.highlight(hk, prov, quote = "a quoted passage", location = "Location 512", capturedAt = now)
        val anchor = highlight.anchor
        assertTrue(anchor is TextAnchor.External)
        anchor as TextAnchor.External
        assertEquals("Location 512", anchor.location)
        assertEquals("a quoted passage", anchor.quote)
        assertEquals("book:isbn:9781449373320", anchor.bookRef)

        val note = CaptureBuilder.quotedNote(nk, highlight, body = "my annotation", capturedAt = now)
        assertEquals(NoteType.PASSAGE_ANCHORED, note.type)
        assertEquals("my annotation", note.body)
        assertEquals(1, note.references.size)
        assertEquals("a quoted passage", note.references.single().quotedSnapshot)
    }

    @Test
    fun manualNoteBuildsFreestandingSynthesisWithNoPassage() {
        val prov = ProvenanceLadder.resolve(RawCapture("typed", appPackage = "com.opaque", capturedAt = now))
        val note = CaptureBuilder.manualNote(nk, prov, body = "a thought over an app", capturedAt = now)
        assertEquals(NoteType.FREESTANDING_SYNTHESIS, note.type)
        assertEquals("a thought over an app", note.body)
        assertTrue(note.references.isEmpty())
        assertEquals("app:com.opaque", note.source.sourceId)
    }

    @Test
    fun bindRepointsTheFrozenDescriptorAtABook() {
        val prov = ProvenanceLadder.resolve(RawCapture("t", url = "https://x.example", capturedAt = now))
        val note = CaptureBuilder.manualNote(nk, prov, body = "b", capturedAt = now)
        assertNull(note.source.bookKey)
        val bound = CaptureBuilder.bind(note, EntityKey("ER", "Book", 3))
        assertEquals(EntityKey("ER", "Book", 3), bound.source.bookKey)
        // Everything else about the note is untouched — same cluster id, body, type.
        assertEquals(note.source.sourceId, bound.source.sourceId)
        assertEquals(note.body, bound.body)
    }
}
