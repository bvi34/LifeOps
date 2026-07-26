package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteResolverTest {

    private fun descriptor(source: SourceType) = SourceDescriptor(
        bookKey = EntityKey("ER", "Book", 1),
        sourceType = source,
        sourceId = null,
        title = "A Book",
        author = null
    )

    private fun flowingRef(quote: String, prefix: String = "", suffix: String = "") =
        PassageReference(quote, TextAnchor.Flowing(0, 0, quote, prefix, suffix))

    @Test
    fun exactMatchInPresentSourceResolves() {
        val text = "The quick brown fox jumps over the lazy dog."
        val res = NoteResolver.resolveReference(flowingRef("brown fox"), text, SourceType.EPUB)
        assertEquals(NoteResolver.State.RESOLVED, res.state)
        assertTrue(res.state.canJump)
        assertEquals(0, res.chapterOrdinal)
        assertEquals("brown fox", text.substring(res.range!!.first, res.range.last + 1))
        assertEquals(NoteResolver.Reliability.RELIABLE, res.reliability)
    }

    @Test
    fun editedPassageResolvesFuzzily() {
        val original = "The quick brown fox jumps over the lazy dog."
        val edited = "The quick brown fox leaps over the lazy dog."
        val res = NoteResolver.resolveReference(
            flowingRef("The quick brown fox jumps over"), edited, SourceType.ROYAL_ROAD
        )
        assertEquals(NoteResolver.State.FUZZY, res.state)
        assertTrue(res.state.canJump)
        // Royal Road is borrowed → even a match is best-effort.
        assertEquals(NoteResolver.Reliability.BEST_EFFORT, res.reliability)
    }

    @Test
    fun deletedPassageOrphansButKeepsSnapshot() {
        val res = NoteResolver.resolveReference(
            flowingRef("a passage that no longer exists here"),
            "totally different replacement text about other things",
            SourceType.ROYAL_ROAD
        )
        assertEquals(NoteResolver.State.ORPHANED, res.state)
        assertFalse(res.state.canJump)
        assertTrue(res.state.isDegraded)
        assertEquals("a passage that no longer exists here", res.frozenSnapshot) // still readable
    }

    @Test
    fun absentSourceIsUnavailableNotOrphaned() {
        val res = NoteResolver.resolveReference(
            flowingRef("some quote"), sourceText = null, sourceType = SourceType.ROYAL_ROAD
        )
        assertEquals(NoteResolver.State.SOURCE_UNAVAILABLE, res.state)
        assertTrue(res.state.isDegraded)
        assertEquals("some quote", res.frozenSnapshot)
    }

    @Test
    fun pdfAnchorResolvesByQuoteOnPage() {
        val ref = PassageReference(
            "positioned glyphs",
            TextAnchor.Pdf(page = 4, quads = emptyList(), quote = "positioned glyphs")
        )
        val pageText = "A line about positioned glyphs that do not reflow."
        val res = NoteResolver.resolveReference(ref, pageText, SourceType.PDF)
        assertEquals(NoteResolver.State.RESOLVED, res.state)
        assertEquals(4, res.page)
        assertEquals(NoteResolver.Reliability.RELIABLE, res.reliability)
    }

    @Test
    fun overallStateIsBestAcrossSynthesisReferences() {
        val note = Note.synthesis(
            key = EntityKey("ER", "Note", 9),
            body = "connecting ideas",
            source = descriptor(SourceType.EPUB),
            references = listOf(
                flowingRef("present passage"),  // will resolve
                flowingRef("gone passage")       // will orphan
            ),
            createdAt = 1L
        )
        val resolutions = NoteResolver.resolveNote(note) { anchor ->
            // Only the first chapter text is available; it contains the present passage.
            "here is the present passage in chapter text"
        }
        // One orphaned, one resolved → overall is the best (RESOLVED): the note is still useful.
        assertEquals(NoteResolver.State.RESOLVED, NoteResolver.overallState(resolutions))
        assertTrue(resolutions.any { it.state == NoteResolver.State.ORPHANED })
    }

    @Test
    fun emptySynthesisIsResolved() {
        assertEquals(NoteResolver.State.RESOLVED, NoteResolver.overallState(emptyList()))
    }
}
