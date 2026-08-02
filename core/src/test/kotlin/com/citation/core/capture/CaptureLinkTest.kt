package com.citation.core.capture

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLinkTest {

    private var seq = 0L
    private fun noteKey() = EntityKey("ER", "Note", ++seq)

    private fun captureNote(
        clusterId: String,
        title: String = "t",
        boundBook: EntityKey? = null
    ): Note = Note.synthesis(
        key = noteKey(),
        body = "b",
        source = SourceDescriptor(
            bookKey = boundBook,
            sourceType = SourceType.CAPTURE,
            sourceId = clusterId,
            title = title,
            author = null
        ),
        references = emptyList(),
        createdAt = seq
    )

    private val book = EntityKey("ER", "Book", 42)

    @Test
    fun bindsEveryProvisionalMemberOfTheCluster() {
        val notes = listOf(
            captureNote("title:Meditations"),
            captureNote("title:Meditations"),
            captureNote("url:other.example")
        )
        val rebound = CaptureLink.link(notes, "title:Meditations", book)
        assertEquals(2, rebound.size)
        assertTrue(rebound.all { it.source.bookKey == book })
    }

    @Test
    fun leavesOtherClustersAlone() {
        val notes = listOf(captureNote("title:A"), captureNote("url:b.example"))
        val rebound = CaptureLink.link(notes, "title:A", book)
        assertEquals(1, rebound.size)
        assertEquals("title:A", rebound.single().source.sourceId)
    }

    @Test
    fun preservesTheFrozenDescriptorApartFromTheBinding() {
        val original = captureNote("title:Dune", title = "Dune")
        val rebound = CaptureLink.link(listOf(original), "title:Dune", book).single()
        // Only the book key changes — sourceType, cluster id, title all survive so the note stays
        // legible and the cluster view stays intact.
        assertEquals(book, rebound.source.bookKey)
        assertEquals(SourceType.CAPTURE, rebound.source.sourceType)
        assertEquals("title:Dune", rebound.source.sourceId)
        assertEquals("Dune", rebound.source.title)
        assertEquals(original.key, rebound.key)
        assertEquals(original.body, rebound.body)
    }

    @Test
    fun neverReBindsAnAlreadyBoundMember() {
        val already = EntityKey("ER", "Book", 7)
        val notes = listOf(
            captureNote("title:X", boundBook = already), // already bound elsewhere — untouched
            captureNote("title:X") // still provisional — the only one linked
        )
        val rebound = CaptureLink.link(notes, "title:X", book)
        assertEquals(1, rebound.size)
        assertEquals(book, rebound.single().source.bookKey)
    }

    @Test
    fun unknownClusterLinksNothing() {
        val rebound = CaptureLink.link(listOf(captureNote("title:A")), "title:missing", book)
        assertTrue(rebound.isEmpty())
    }

    @Test
    fun ignoresNotesWithNoClusterId() {
        val epubNote = Note.synthesis(
            key = noteKey(),
            body = "b",
            source = SourceDescriptor(null, SourceType.EPUB, sourceId = null, title = "t", author = null),
            references = emptyList(),
            createdAt = 0
        )
        assertNull(epubNote.source.sourceId)
        assertTrue(CaptureLink.link(listOf(epubNote), "title:A", book).isEmpty())
    }
}
