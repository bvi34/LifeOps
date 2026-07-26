package com.citation.core.capture

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureTriageTest {

    private var seq = 0L
    private fun capture(clusterId: String, boundBook: EntityKey? = null): Note = Note.synthesis(
        key = EntityKey("ER", "Note", ++seq),
        body = "b",
        source = SourceDescriptor(boundBook, SourceType.CAPTURE, clusterId, "t", null),
        references = emptyList(),
        createdAt = seq
    )

    @Test
    fun queueSurfacesOnlyThinUnboundClusters() {
        val notes = listOf(
            capture("app:com.opaque"),          // thin
            capture("app:com.opaque"),          // same thin cluster
            capture("ts:169"),                  // thin
            capture("url:https://x.example"),   // provisional but not thin
            capture("title:some book"),         // provisional but not thin
            capture("app:com.bound", boundBook = EntityKey("ER", "Book", 1)) // thin rung but bound → not triage
        )
        val queue = CaptureTriage.queue(notes)
        assertEquals(setOf("app:com.opaque", "ts:169"), queue.map { it.clusterId }.toSet())
        // Three notes across the two thin clusters (two in app:com.opaque, one in ts:169).
        assertEquals(3, CaptureTriage.pendingCount(notes))
    }

    @Test
    fun needsTriageMatchesThinProvenance() {
        val now = 1_690_000_000_000L
        assertTrue(CaptureTriage.needsTriage(ProvenanceLadder.resolve(RawCapture("t", appPackage = "com.x", capturedAt = now))))
        assertTrue(CaptureTriage.needsTriage(ProvenanceLadder.resolve(RawCapture("t", capturedAt = now))))
        assertFalse(CaptureTriage.needsTriage(ProvenanceLadder.resolve(RawCapture("t", url = "https://x", capturedAt = now))))
        assertFalse(CaptureTriage.needsTriage(ProvenanceLadder.resolve(RawCapture("t", title = "A Book", capturedAt = now))))
    }
}
