package com.citation.core.note

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {

    private fun note(id: Int, tags: List<String>): Note = Note(
        key = EntityKey("ER", "Note", id.toLong()),
        type = NoteType.FREESTANDING_SYNTHESIS,
        body = "",
        source = SourceDescriptor(null, SourceType.EPUB, null, "Book", null),
        references = emptyList(),
        createdAt = id.toLong(),
        tags = tags
    )

    @Test
    fun parseSplitsOnSpacesCommasAndNewlines_stripsHashAndLowercases() {
        assertEquals(
            listOf("stoicism", "focus", "deep-work"),
            Tags.parse("#Stoicism, focus\n#Deep-Work")
        )
    }

    @Test
    fun parseDedupesPreservingFirstSeenOrderAndDropsBlanks() {
        assertEquals(listOf("a", "b"), Tags.parse("a  b   a ,, #A"))
    }

    @Test
    fun formatRoundTripsThroughParse() {
        val tags = listOf("stoicism", "deep-work")
        assertEquals(tags, Tags.parse(Tags.format(tags)))
    }

    @Test
    fun countsAreSortedByFrequencyThenAlphabetically() {
        val notes = listOf(
            note(1, listOf("focus", "stoicism")),
            note(2, listOf("focus")),
            note(3, listOf("agency", "focus"))
        )
        assertEquals(
            listOf(TagCount("focus", 3), TagCount("agency", 1), TagCount("stoicism", 1)),
            Tags.counts(notes)
        )
    }

    @Test
    fun withTagFiltersByNormalizedExactMatch() {
        val notes = listOf(note(1, listOf("focus")), note(2, listOf("stoicism")))
        assertEquals(listOf(1L), Tags.withTag(notes, "#Focus").map { it.key.sequence })
    }
}
