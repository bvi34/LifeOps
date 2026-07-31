package com.citation.core.note

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {

    private fun note(
        id: Int,
        body: String = "",
        snapshot: String = "",
        title: String = "A Book",
        author: String? = "An Author",
        tags: List<String> = emptyList()
    ): Note = Note(
        key = EntityKey("ER", "Note", id.toLong()),
        type = NoteType.FREESTANDING_SYNTHESIS,
        body = body,
        source = SourceDescriptor(null, SourceType.EPUB, null, title, author),
        references = if (snapshot.isEmpty()) emptyList()
        else listOf(PassageReference(snapshot, TextAnchor.Flowing(0, 0, snapshot, "", ""))),
        createdAt = id.toLong(),
        tags = tags
    )

    private val corpus = listOf(
        note(1, body = "Deliberate practice is the key to mastery"),
        note(2, snapshot = "The clocks were striking thirteen", title = "1984", author = "Orwell"),
        note(3, body = "note about focus", tags = listOf("deep-work")),
        note(4, body = "unrelated musing")
    )

    @Test
    fun blankQueryReturnsEverythingUnchanged() {
        assertEquals(corpus, NoteSearch.match(corpus, "   "))
    }

    @Test
    fun matchesBodyCaseInsensitively() {
        val hits = NoteSearch.match(corpus, "MASTERY")
        assertEquals(listOf(1L), hits.map { it.key.sequence })
    }

    @Test
    fun matchesFrozenSnapshotAndTitleAndAuthor() {
        assertEquals(listOf(2L), NoteSearch.match(corpus, "thirteen").map { it.key.sequence })
        assertEquals(listOf(2L), NoteSearch.match(corpus, "orwell").map { it.key.sequence })
        assertEquals(listOf(2L), NoteSearch.match(corpus, "1984").map { it.key.sequence })
    }

    @Test
    fun matchesTags() {
        assertEquals(listOf(3L), NoteSearch.match(corpus, "deep-work").map { it.key.sequence })
    }

    @Test
    fun allTokensMustMatch_andSemantics() {
        // "clocks" is in note 2's snapshot but "focus" is not — AND across tokens yields nothing.
        assertTrue(NoteSearch.match(corpus, "clocks focus").isEmpty())
        // Both tokens live in note 1's body.
        assertEquals(listOf(1L), NoteSearch.match(corpus, "practice mastery").map { it.key.sequence })
    }

    @Test
    fun preservesInputOrder() {
        val hits = NoteSearch.match(corpus, "a") // substring 'a' is broad on purpose
        assertEquals(hits.sortedBy { it.key.sequence }, hits)
    }
}
