package com.citation.core

import com.citation.core.epub.EpubParser
import com.citation.core.key.EntityType
import com.citation.core.key.KeyAllocator
import com.citation.core.model.Book
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import com.citation.core.sync.Mailbox
import com.citation.core.sync.NotePacket
import com.citation.core.sync.TelemetryPacket
import com.citation.core.sync.UpPacket

/**
 * The **walking skeleton**, wired end to end in one place: EPUB bytes → internal [Book] → mint a key
 * → capture one highlight → attach one note → emit packets up the mailbox.
 *
 * This is the vertical slice the task list recommends proving first — not breadth, but *the pipe*.
 * Every other source becomes "add a producer" that yields a [Book], and every other feature bolts
 * onto this spine. The Android reader drives the same calls; this class exists so the slice is
 * exercised by pure JVM tests with no device.
 *
 * It is intentionally a thin coordinator over the real components — it holds the [KeyAllocator] and
 * the up-bound [Mailbox], and does no persistence itself (that's the Android layer's Room stores).
 */
class CitationSkeleton(
    private val keys: KeyAllocator = KeyAllocator(),
    val outbox: Mailbox<UpPacket, Nothing> = Mailbox()
) {

    /** Parse an EPUB and mint the book its own key. Returns `null` if the archive yields no content. */
    fun ingestEpub(bytes: ByteArray): IngestedBook? {
        val parsed = EpubParser.parse(bytes) ?: return null
        val key = keys.next(EntityType.BOOK)
        val book = parsed.book.copy(key = key)
        val descriptor = SourceDescriptor(
            bookKey = key,
            sourceType = book.metadata.source,
            sourceId = (parsed.identity.strongest as? com.citation.core.identity.IdentityKey.Isbn)?.normalized,
            title = book.metadata.title,
            author = book.metadata.author
        )
        return IngestedBook(book, descriptor)
    }

    /**
     * Capture a highlight over a flowing-text selection in [ingested], attach [noteBody] as a
     * passage-anchored note, and post the note up the mailbox. Returns the note (already frozen).
     */
    fun captureNote(
        ingested: IngestedBook,
        chapterOrdinal: Int,
        selectionStart: Int,
        selectionEnd: Int,
        noteBody: String,
        now: Long
    ): Note {
        val chapter = ingested.book.chapterAt(chapterOrdinal)
            ?: error("No chapter $chapterOrdinal in ${ingested.book.metadata.title}")
        val highlight = Highlight.captureFlowing(
            key = keys.next(EntityType.HIGHLIGHT),
            source = ingested.source,
            chapterText = chapter.text,
            chapterOrdinal = chapterOrdinal,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            createdAt = now
        )
        val note = Note.anchored(
            key = keys.next(EntityType.NOTE),
            body = noteBody,
            highlight = highlight,
            createdAt = now
        )
        outbox.post(NotePacket.of(note))
        return note
    }

    /** Report reading time for [ingested] up the mailbox. */
    fun reportReading(ingested: IngestedBook, minutes: Int, occurredAt: Long) {
        outbox.post(
            TelemetryPacket(
                bookKey = ingested.book.key,
                sourceType = ingested.book.metadata.source,
                title = ingested.book.metadata.title,
                minutesRead = minutes,
                occurredAt = occurredAt
            )
        )
    }

    /** A parsed, keyed book plus the frozen descriptor notes/highlights hang off. */
    data class IngestedBook(val book: Book, val source: SourceDescriptor)
}
