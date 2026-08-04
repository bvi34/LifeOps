package com.citation.core.capture

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor

/**
 * Turns a resolved [CaptureProvenance] into the note-store records a capture becomes — **purely**, so
 * the whole "capture → packet with a source" mapping is unit-testable without keys, storage, or
 * Android. The Android layer only supplies minted [EntityKey]s and persists what comes back.
 *
 * A capture is exactly what the feature brief says it is: *a note packet with a source*. The source
 * is a [SourceType.CAPTURE] descriptor whose `sourceId` is the provenance's self-describing cluster
 * id, `bookKey` is `null` (provisional until promotion binds it), and `title` is the provenance's
 * display title. Two shapes come out of here:
 *  - a **quoted capture** (a browser selection, a Kindle highlight): a [Highlight] anchored by an
 *    opaque [TextAnchor.External] location token, with a passage-anchored [Note] hung off it;
 *  - a **manual note** (the floating bubble — you typed a thought over some app): a freestanding
 *    synthesis [Note] with no passage, because there is nothing on screen we could quote.
 */
object CaptureBuilder {

    /** The frozen, source-legible descriptor a captured note carries. Book key is null until promoted. */
    fun descriptor(provenance: CaptureProvenance): SourceDescriptor = SourceDescriptor(
        bookKey = null,
        sourceType = SourceType.CAPTURE,
        sourceId = provenance.clusterId,
        title = provenance.displayTitle,
        author = provenance.author
    )

    /**
     * Build a highlight from a quoted cross-app capture. The [location] (a URL, a Kindle location
     * token, a page fragment) becomes the [TextAnchor.External] jump target — best-effort by nature,
     * since it points back into an app we don't control — and the quote is frozen for legibility.
     */
    fun highlight(
        highlightKey: EntityKey,
        provenance: CaptureProvenance,
        quote: String,
        location: String?,
        capturedAt: Long
    ): Highlight = Highlight(
        key = highlightKey,
        source = descriptor(provenance),
        quotedSnapshot = quote,
        anchor = TextAnchor.External(
            location = location.orEmpty(),
            quote = quote,
            bookRef = provenance.clusterId
        ),
        createdAt = capturedAt
    )

    /** A passage-anchored note over a captured [highlight]; [body] is your annotation (may be blank). */
    fun quotedNote(noteKey: EntityKey, highlight: Highlight, body: String, capturedAt: Long): Note =
        Note.anchored(noteKey, body, highlight, capturedAt)

    /** A manual, freestanding note captured over an app that exposed nothing to quote. */
    fun manualNote(
        noteKey: EntityKey,
        provenance: CaptureProvenance,
        body: String,
        capturedAt: Long
    ): Note = Note.synthesis(
        key = noteKey,
        body = body,
        source = descriptor(provenance),
        references = emptyList(),
        createdAt = capturedAt
    )

    /** Re-point a note's frozen descriptor at a now-bound book (the persisted effect of a promotion). */
    fun bind(note: Note, bookKey: EntityKey?): Note =
        note.copy(source = note.source.copy(bookKey = bookKey))
}
