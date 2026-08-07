package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAnchorTest {

    private fun ref(location: String, bookRef: String? = "9781492082279") = PassageReference(
        quotedSnapshot = "a quoted passage from the licensed book",
        anchor = TextAnchor.External(location = location, quote = "a quoted passage", bookRef = bookRef)
    )

    @Test
    fun externalWithLocationIsJumpableBestEffort() {
        // Read-in-place: no local text, but a location token means we can deep-link back.
        val res = NoteResolver.resolveReference(ref("epubcfi(/6/14)"), sourceText = null, sourceType = SourceType.OREILLY)
        assertEquals(NoteResolver.State.RESOLVED, res.state)
        assertTrue(res.state.canJump)
        assertEquals(NoteResolver.Reliability.BEST_EFFORT, res.reliability)
        assertEquals("a quoted passage from the licensed book", res.frozenSnapshot)
    }

    @Test
    fun externalWithoutLocationButKnownBookStaysLinked() {
        // No exact location token, but the capture auto-filled the book (ISBN). The note can still
        // reopen its book, so it's linked (best-effort) — not orphaned.
        val res = NoteResolver.resolveReference(ref(""), sourceText = null, sourceType = SourceType.OREILLY)
        assertEquals(NoteResolver.State.RESOLVED, res.state)
        assertTrue(res.state.canJump)
        assertEquals("a quoted passage from the licensed book", res.frozenSnapshot)
    }

    @Test
    fun externalWithNeitherLocationNorBookOrphans() {
        // Knows neither where in the reader nor which book — nothing to open.
        val res = NoteResolver.resolveReference(ref("", bookRef = null), sourceText = null, sourceType = SourceType.OREILLY)
        assertEquals(NoteResolver.State.ORPHANED, res.state)
        assertFalse(res.state.canJump)
        assertTrue(res.state.isDegraded)
        assertEquals("a quoted passage from the licensed book", res.frozenSnapshot)
    }

    @Test
    fun externalIgnoresAbsentSourceText() {
        // Unlike owned/borrowed anchors, a null sourceText does NOT mean "source unavailable" for
        // read-in-place — the source is O'Reilly's reader, reached by deep link, not local text.
        val res = NoteResolver.resolveReference(ref("frag-42"), sourceText = null, sourceType = SourceType.OREILLY)
        assertTrue(res.state != NoteResolver.State.SOURCE_UNAVAILABLE)
    }
}
