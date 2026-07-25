package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {

    private val source = SourceDescriptor(
        bookKey = EntityKey("ER", "Book", 1),
        sourceType = SourceType.EPUB,
        sourceId = "9780132350884",
        title = "The Test Book",
        author = "Ada Lovelace"
    )

    @Test
    fun highlightCaptureFreezesSnapshotAndContext() {
        val text = "It was a bright cold day in April, and the clocks were striking thirteen"
        val start = text.indexOf("the clocks")
        val end = text.indexOf("thirteen") + "thirteen".length
        val hl = Highlight.captureFlowing(
            key = EntityKey("ER", "Highlight", 1),
            source = source,
            chapterText = text,
            chapterOrdinal = 0,
            selectionStart = start,
            selectionEnd = end,
            createdAt = 1000L
        )
        assertEquals("the clocks were striking thirteen", hl.quotedSnapshot)
        val anchor = hl.anchor as TextAnchor.Flowing
        assertTrue(anchor.prefix.endsWith("April, and "))
        assertEquals("", anchor.suffix) // selection runs to end of text
    }

    @Test
    fun passageAnchoredNoteHasExactlyOneReference() {
        val hl = Highlight.captureFlowing(
            EntityKey("ER", "Highlight", 1), source,
            "some passage text here", 0, 0, 4, 1000L
        )
        val note = Note.anchored(EntityKey("ER", "Note", 1), "my thought", hl, 2000L)
        assertEquals(NoteType.PASSAGE_ANCHORED, note.type)
        assertEquals(1, note.references.size)
        assertEquals("some", note.references[0].quotedSnapshot)
    }

    @Test
    fun synthesisNoteMayReferenceSeveralOrNone() {
        val note = Note.synthesis(
            key = EntityKey("ER", "Note", 2),
            body = "connecting two ideas",
            source = source,
            references = listOf(
                PassageReference("idea one", TextAnchor.Flowing(0, 0, "idea one")),
                PassageReference("idea two", TextAnchor.Flowing(3, 0, "idea two"))
            ),
            createdAt = 3000L
        )
        assertEquals(NoteType.FREESTANDING_SYNTHESIS, note.type)
        assertEquals(2, note.references.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun passageAnchoredRejectsWrongReferenceCount() {
        Note(
            key = EntityKey("ER", "Note", 3),
            type = NoteType.PASSAGE_ANCHORED,
            body = "x",
            source = source,
            references = emptyList(),
            createdAt = 1L
        )
    }

    @Test
    fun noteIsSelfSufficientWithoutTheSource() {
        // The frozen snapshot + descriptor mean an orphaned note is degraded, not lost.
        val note = Note.synthesis(
            EntityKey("ER", "Note", 4), "still legible", source,
            listOf(PassageReference("the frozen passage", TextAnchor.Flowing(0, 0, "the frozen passage"))),
            1L
        )
        assertEquals("The Test Book", note.source.title)
        assertEquals("the frozen passage", note.references[0].quotedSnapshot)
    }
}
