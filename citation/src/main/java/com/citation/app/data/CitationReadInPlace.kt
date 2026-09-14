package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityType
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.ReadingState

/**
 * Reading in place on somebody else's site — O'Reilly and Kindle — and the capture context that
 * says which book a highlight taken there belongs to.

 * Nothing here downloads a book. What it captures is what the reader selected, filed against a
 * record Citation keeps of a book it does not own.
 */

/** The reader marks a read-in-place book open, so a self-capture can be filed against it. */
internal fun CitationRepository.beginReaderContext(bookKey: String) { openReaderBookKey = bookKey }

/** The reader marks its book closed. No-ops if a different book has since opened. */
internal fun CitationRepository.endReaderContext(bookKey: String) {
    if (openReaderBookKey == bookKey) openReaderBookKey = null
}

/**
 * File a capture that arrived from **within our own container app** — the system "Save to Citation"
 * used on a selection in a read-in-place reader — against the book currently open in that reader,
 * exactly as the reader's own "Add note" would: a real [SourceType.OREILLY] source (title/author +
 * bound `bookKey`) and the reader's saved position token as the [TextAnchor.External] jump target.
 * This is what makes a read-in-place capture **report where it came from** instead of falling to
 * the container's app-name floor. Returns `null` when no read-in-place reader is open, so the
 * caller falls back to the normal cross-app provenance ladder.
 */
internal suspend fun CitationRepository.captureInOpenReader(
    quote: String,
    annotation: String = "",
    now: Long = System.currentTimeMillis()
): Note? {
    val bookKey = openReaderBookKey ?: return null
    val entity = db.bookDao().get(bookKey) ?: return null
    return captureExternalNote(bookKey, entity.externalLocation.orEmpty(), quote, annotation, now)
}

// --- O'Reilly (read-in-place) -------------------------------------------------------------

/** Build a browse session at O'Reilly's catalog, proxied through your library with your card/PIN. */
internal fun CitationRepository.oreillyCatalog(): OreillyCatalog =
    OreillyCatalog(
        startUrl = com.citation.core.oreilly.OreillyLink.browseUrl(oreillyAccess.proxy()),
        login = oreillyAccess.credentials()
    )

/**
 * Register an O'Reilly book for read-in-place. **No content is cached** — it's licensed — only a
 * sovereign [BookEntity] holding your layer (id, title, last position, notes). Deduped by book id.
 */
internal suspend fun CitationRepository.addOreillyBook(
    bookId: String,
    title: String,
    author: String? = null,
    now: Long = System.currentTimeMillis()
): String {
    db.bookDao().findBySource(bookId, SourceType.OREILLY.name)?.let { return it.key }
    val key = keys.next(EntityType.BOOK)
    db.bookDao().upsert(
        BookEntity(
            key = key.toString(),
            title = title,
            author = author,
            sourceType = SourceType.OREILLY.name,
            sourceId = bookId,
            language = null,
            acquisitionState = AcquisitionState.ACQUIRED.name,
            readingState = ReadingState.READING.name,
            createdAt = now
        )
    )
    checkpointKey(EntityType.BOOK)
    return key.toString()
}

/**
 * Build the deep link that lands you at your saved O'Reilly position in one tap (else the cover).
 * Routes through your configured library proxy so you reach the content on a library card, and
 * attaches your stored card/PIN for auto-reauth when the proxy session lapses.
 */
internal suspend fun CitationRepository.oreillySession(bookKey: String): OreillySession? {
    val entity = db.bookDao().get(bookKey) ?: return null
    val bookId = entity.sourceId ?: return null
    val link = com.citation.core.oreilly.OreillyLink.deepLink(
        bookId, entity.externalLocation, oreillyAccess.proxy()
    )
    return OreillySession(bookKey, bookId, link, oreillyAccess.credentials())
}

// --- Kindle (read-in-place on read.amazon.com) --------------------------------------------

/** Build a browse session at your Kindle library (the Cloud Reader's book grid). */
internal fun CitationRepository.kindleLibrary(): KindleLibrary =
    KindleLibrary(startUrl = com.citation.core.kindle.KindleLink.libraryUrl())

/**
 * Register a Kindle book for read-in-place. **No content is cached** — it's licensed — only a
 * sovereign [BookEntity] holding your layer (ASIN, title, last position label, notes). Deduped by
 * ASIN, exactly like [addOreillyBook].
 */
internal suspend fun CitationRepository.addKindleBook(
    asin: String,
    title: String,
    author: String? = null,
    now: Long = System.currentTimeMillis()
): String {
    db.bookDao().findBySource(asin, SourceType.KINDLE.name)?.let { return it.key }
    val key = keys.next(EntityType.BOOK)
    db.bookDao().upsert(
        BookEntity(
            key = key.toString(),
            title = title,
            author = author,
            sourceType = SourceType.KINDLE.name,
            sourceId = asin,
            language = null,
            acquisitionState = AcquisitionState.ACQUIRED.name,
            readingState = ReadingState.READING.name,
            createdAt = now
        )
    )
    checkpointKey(EntityType.BOOK)
    return key.toString()
}

/** Build the session that opens a Kindle book in the Cloud Reader (Whispersync lands you at your spot). */
internal suspend fun CitationRepository.kindleSession(bookKey: String): KindleSession? {
    val entity = db.bookDao().get(bookKey) ?: return null
    val asin = entity.sourceId ?: return null
    return KindleSession(bookKey, asin, com.citation.core.kindle.KindleLink.readerUrl(asin))
}
