package com.citation.core.capture

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureClustererTest {

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

    @Test
    fun groupsNotesSharingAnIdentifier() {
        val notes = listOf(
            captureNote("url:https://a.example", "A"),
            captureNote("url:https://a.example", "A"),
            captureNote("url:https://b.example", "B")
        )
        val clusters = CaptureClusterer.clusterNotes(notes)
        assertEquals(2, clusters.size)
        val a = clusters.first { it.clusterId == "url:https://a.example" }
        assertEquals(2, a.memberKeys.size)
        assertEquals(ProvenanceRung.URL, a.rung)
        assertTrue(a.isProvisional)
        assertFalse(a.isThin) // a URL is provisional but not thin
    }

    @Test
    fun thinClusterIsAppOrTimestampAndUnbound() {
        val clusters = CaptureClusterer.clusterNotes(
            listOf(
                captureNote("app:com.opaque", "opaque"),
                captureNote("ts:169", "loose thought")
            )
        )
        assertTrue(clusters.all { it.isThin })
    }

    @Test
    fun boundClusterIsNoLongerProvisionalOrThin() {
        val book = EntityKey("ER", "Book", 7)
        val clusters = CaptureClusterer.clusterNotes(
            listOf(
                captureNote("app:com.opaque", "opaque", boundBook = book),
                captureNote("app:com.opaque", "opaque") // one still-unbound member
            )
        )
        assertEquals(1, clusters.size)
        val c = clusters.single()
        assertEquals(book, c.boundBookKey)
        assertFalse("a bound cluster is never demoted back to provisional", c.isProvisional)
        assertFalse(c.isThin)
        assertEquals(2, c.memberKeys.size)
    }

    @Test
    fun preservesFirstSeenOrder() {
        val clusters = CaptureClusterer.clusterNotes(
            listOf(captureNote("url:z"), captureNote("url:a"), captureNote("url:z"))
        )
        assertEquals(listOf("url:z", "url:a"), clusters.map { it.clusterId })
    }

    @Test
    fun idlessNotesAreIgnored() {
        val epubNote = Note.synthesis(
            key = noteKey(),
            body = "b",
            source = SourceDescriptor(null, SourceType.EPUB, sourceId = null, title = "t", author = null),
            references = emptyList(),
            createdAt = 0
        )
        assertTrue(CaptureClusterer.clusterNotes(listOf(epubNote)).isEmpty())
        assertNull(CaptureClusterer.memberOf(epubNote))
    }

    @Test
    fun representativeTitleIsFirstNonBlank() {
        val clusters = CaptureClusterer.clusterNotes(
            listOf(captureNote("url:x", ""), captureNote("url:x", "Real Title"))
        )
        assertEquals("Real Title", clusters.single().displayTitle)
    }
}
