package com.citation.app.data

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.model.Book
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import com.citation.core.pdf.PdfFlow
import com.citation.core.sync.NotePacket

/**
 * O'Reilly library access: the encrypted card number, PIN and proxy host that reach a library's
 * subscription.
 *
 * Never synced and never in the database in the clear — a library card is a credential, and the one
 * that opens a subscription is the one most worth not leaking.
 */

/** Current O'Reilly access config for Settings — proxy host and whether a card/PIN are on file. */
internal fun CitationRepository.oreillyAccessConfig(): OreillyAccess.Config = oreillyAccess.config()

/** Save the library proxy host (blank restores the default). */
internal fun CitationRepository.setOreillyProxyHost(host: String) {
    oreillyAccess.setProxyHost(host.ifBlank { com.citation.core.oreilly.OreillyLibraryProxy.MID_CONTINENT_HOST })
}

/** Save (encrypted) your library card + PIN for auto-reauth. */
internal fun CitationRepository.setOreillyCredentials(card: String, pin: String) = oreillyAccess.setCredentials(card, pin)

/** Forget the stored card + PIN (the proxy host stays). */
internal fun CitationRepository.clearOreillyCredentials() = oreillyAccess.clearCredentials()

/**
 * Whether the warm page cache for [bookKey] has gone stale (untouched past the TTL) and should be
 * dropped. Read the last-open time *before* [markOpened] restamps it, so the decision reflects how
 * long the cache has actually sat. A never-opened book is never stale.
 */
internal suspend fun CitationRepository.oreillyWarmCacheStale(bookKey: String, now: Long = System.currentTimeMillis()): Boolean {
    val entity = db.bookDao().get(bookKey) ?: return false
    return com.citation.core.oreilly.OreillyCachePolicy.shouldPurge(
        lastWarmedAt = entity.lastOpenedAt, isReaderOpen = false, now = now
    )
}

/** Persist the O'Reilly reader's last position token so reopening lands at your spot. */
internal suspend fun CitationRepository.saveExternalPosition(bookKey: String, location: String) {
    db.bookDao().saveExternalLocation(bookKey, location)
}

/**
 * Capture a note on a read-in-place source (O'Reilly): your layer only — a frozen quote plus the
 * reader's location token as an [TextAnchor.External] anchor. Content stays theirs; the note is
 * yours and sovereign.
 */
internal suspend fun CitationRepository.captureExternalNote(
    bookKey: String,
    location: String,
    quote: String,
    body: String,
    now: Long = System.currentTimeMillis()
): Note {
    val entity = db.bookDao().get(bookKey) ?: error("no book $bookKey")
    val descriptor = SourceDescriptor(
        bookKey = EntityKey.parse(bookKey),
        sourceType = SourceType.valueOf(entity.sourceType),
        sourceId = entity.sourceId,
        title = entity.title,
        author = entity.author
    )
    val highlight = Highlight(
        key = keys.next(EntityType.HIGHLIGHT),
        source = descriptor,
        quotedSnapshot = quote,
        anchor = TextAnchor.External(location = location, quote = quote, bookRef = entity.sourceId),
        createdAt = now
    )
    val note = Note.anchored(keys.next(EntityType.NOTE), body, highlight, now)
    val versioned = mailbox.post(NotePacket.of(note))
    db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.HIGHLIGHT)
    checkpointKey(EntityType.NOTE)
    persistSyncState()
    return note
}

/**
 * Capture a highlight over a flowing-text selection and attach a passage-anchored note, then
 * queue the note up the mailbox and persist everything (highlight, note, sync bookkeeping).
 */
internal suspend fun CitationRepository.captureNote(
    book: Book,
    chapterOrdinal: Int,
    selectionStart: Int,
    selectionEnd: Int,
    noteBody: String,
    now: Long = System.currentTimeMillis(),
    // The colour the reader is currently filing in. Defaulted so every other caller — and the
    // tests — keep working without knowing that highlights have colours at all.
    color: HighlightColor = HighlightColor.YELLOW
): Note {
    val chapter = book.chapterAt(chapterOrdinal) ?: error("no chapter $chapterOrdinal")
    val descriptor = descriptorFor(book)
    val highlight = highlightFor(book, chapter, chapterOrdinal, selectionStart, selectionEnd, descriptor, now)
    val note = Note.anchored(keys.next(EntityType.NOTE), noteBody, highlight, now, color)

    val versioned = mailbox.post(NotePacket.of(note))

    db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.HIGHLIGHT)
    checkpointKey(EntityType.NOTE)
    persistSyncState()
    return note
}

/**
 * Build the highlight for a flowing-text selection, typed by what the book actually *is*.
 *
 * Every source but one anchors by chapter ordinal + quote. A **reflowed PDF** is the exception:
 * its chapters are a derived view of a fixed-page document, so the durable citation is the
 * *page*, not the chapter — and the page is what lets the note jump back to the paged reader, or
 * survive a rebuilt reflow that chunks the pages differently. So a selection made in the flowing
 * reader over a reflowed PDF still produces the [TextAnchor.Pdf] the PDF track already uses
 * (with no glyph quads — the selection came from text, not from rectangles on a bitmap), which
 * keeps one anchor type per source and leaves the sync contract untouched.
 */
internal fun CitationRepository.highlightFor(
    book: Book,
    chapter: Chapter,
    chapterOrdinal: Int,
    selectionStart: Int,
    selectionEnd: Int,
    descriptor: SourceDescriptor,
    now: Long
): Highlight {
    if (book.metadata.source != SourceType.PDF || !PdfFlow.isFlowRef(chapter.sourceRef)) {
        return Highlight.captureFlowing(
            key = keys.next(EntityType.HIGHLIGHT),
            source = descriptor,
            chapterText = chapter.text,
            chapterOrdinal = chapterOrdinal,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            createdAt = now
        )
    }
    val start = selectionStart.coerceIn(0, chapter.text.length)
    val end = selectionEnd.coerceIn(start, chapter.text.length)
    val quote = chapter.text.substring(start, end)
    val page = PdfFlow.pageOf(chapter, start) ?: 0
    return Highlight(
        key = keys.next(EntityType.HIGHLIGHT),
        source = descriptor,
        quotedSnapshot = quote,
        anchor = com.citation.core.pdf.PdfTrack.anchor(page, quote, emptyList()),
        createdAt = now
    )
}

/**
 * Capture a **freestanding synthesis** note — your own artifact that may cite several passages.
 * Each quote is located in the book and anchored; quotes that aren't found are dropped (the
 * synthesis still stands on its own text). The note is queued and persisted like any other.
 */
internal suspend fun CitationRepository.captureSynthesis(
    book: Book,
    citedQuotes: List<String>,
    body: String,
    now: Long = System.currentTimeMillis()
): Note {
    val references = citedQuotes.mapNotNull { quote -> anchorQuoteInBook(book, quote) }
    val note = Note.synthesis(
        key = keys.next(EntityType.NOTE),
        body = body,
        source = descriptorFor(book),
        references = references,
        createdAt = now
    )
    val versioned = mailbox.post(NotePacket.of(note))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.NOTE)
    persistSyncState()
    return note
}
