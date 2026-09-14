package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.app.data.db.KeyWatermarkEntity
import com.citation.app.data.db.SyncStateEntity
import com.citation.core.anchor.TextAnchor
import com.citation.core.capture.CaptureBuilder
import com.citation.core.capture.CapturePromotion
import com.citation.core.doc.DocumentBlock
import com.citation.core.epub.EpubParser
import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.note.PassageReference
import com.citation.core.note.SourceDescriptor
import com.citation.core.sync.BindOrCreate
import com.citation.core.sync.FileEnvelopeStore
import com.citation.core.sync.NotePacket
import com.citation.core.sync.SyncEngine
import kotlinx.coroutines.flow.map

/**
 * The plumbing several concerns share: minting a source descriptor, anchoring a quote back
 * into a book, stamping the sync checkpoint, draining the outbox, and storing a book's images.

 * Here rather than in whichever file happened to need it first. Every one of these is called from
 * two or more of the concerns around it, and a copy in each is a copy that drifts — an outbox
 * flushed one way from a capture and another way from a catalog import is two sync behaviours
 * wearing one name.
 */

/**
 * When a real book record appears, bind any provisional capture clusters it matches to it —
 * **promotion**. Re-points each promoted note's frozen descriptor at [bookKey] and re-posts it so
 * LifeOps sees the binding. Returns the number of notes promoted.
 */
internal suspend fun CitationRepository.promoteCapturesTo(record: CapturePromotion.PromotableRecord): Int {
    val clusters = provisionalSources()
    val promotions = CapturePromotion.promote(record, clusters)
    var moved = 0
    promotions.forEach { promotion ->
        promotion.memberKeys.forEach { noteKey ->
            val entity = db.noteDao().get(noteKey.toString()) ?: return@forEach
            val rebound = CaptureBuilder.bind(CitationMappers.noteFromEntity(entity), promotion.bookKey)
            val versioned = mailbox.post(NotePacket.of(rebound))
            db.noteDao().upsert(CitationMappers.noteToEntity(rebound, versioned.version))
            moved++
        }
    }
    if (moved > 0) persistSyncState()
    return moved
}

/** Build a bind-or-create candidate from a stored book, recovering its identity by source type. */
internal fun CitationRepository.toCandidate(e: BookEntity): BindOrCreate.Candidate {
    val identity = when (e.sourceType) {
        SourceType.EPUB.name -> e.sourceId?.let { IdentitySet(IdentityKey.Isbn(it)) }
        SourceType.PDF.name -> e.sourceId?.let { IdentitySet(IdentityKey.PdfSha(it)) }
        SourceType.ROYAL_ROAD.name ->
            e.sourceId?.toLongOrNull()?.let { IdentitySet(IdentityKey.RoyalRoadId(it)) }
        SourceType.AO3.name ->
            e.sourceId?.toLongOrNull()?.let { IdentitySet(IdentityKey.Ao3Id(it)) }
        else -> null
    } ?: IdentitySet(emptyList())
    return BindOrCreate.Candidate(
        key = EntityKey.parse(e.key)!!,
        identity = identity,
        title = e.title,
        author = e.author,
        lifecycle = CitationMappers.lifecycleOf(e)
    )
}

/** Build a note's frozen source descriptor from the book's stored identity (title/author/id). */
internal suspend fun CitationRepository.descriptorFor(book: Book): SourceDescriptor {
    val entity = book.key?.let { db.bookDao().get(it.toString()) }
    return SourceDescriptor(
        bookKey = book.key,
        sourceType = book.metadata.source,
        sourceId = entity?.sourceId,
        title = book.metadata.title,
        author = book.metadata.author
    )
}

/** Find [quote] in [book] and build a flowing anchor with surrounding context, or null if absent. */
internal fun CitationRepository.anchorQuoteInBook(book: Book, quote: String): PassageReference? {
    for (chapter in book.chapters) {
        val idx = chapter.text.indexOf(quote)
        if (idx >= 0) {
            val prefix = chapter.text.substring(maxOf(0, idx - 32), idx)
            val end = idx + quote.length
            val suffix = chapter.text.substring(end, minOf(chapter.text.length, end + 32))
            return PassageReference(
                quotedSnapshot = quote,
                anchor = TextAnchor.Flowing(chapter.ordinal, idx, quote, prefix, suffix)
            )
        }
    }
    return null
}

internal suspend fun CitationRepository.checkpointKey(type: String) {
    db.syncStateDao().saveWatermark(KeyWatermarkEntity(type, keys.highWater(type)))
}

internal suspend fun CitationRepository.persistSyncState() {
    db.syncStateDao().saveSyncState(
        SyncStateEntity(0, mailbox.currentOutVersion, mailbox.inboxCursor)
    )
    flushOutbox()
}

/**
 * Mirror the up-mailbox to the file-drop so LifeOps can pick up new telemetry/notes promptly —
 * right when they're captured, not only on the periodic/manual [sync] round. Every capture path
 * calls [persistSyncState] immediately after posting (once per capture, once per batch import), so
 * routing the flush through here covers them all without a per-note write storm.
 *
 * Writes only the outbound envelope (no inbound read/reconcile), so it's cheap. Best-effort: a
 * transient IO failure must never fail the capture that triggered it — the next round resends
 * anyway, since the in-memory outbox still holds the unacked packets.
 */
internal fun CitationRepository.flushOutbox() {
    runCatching {
        FileEnvelopeStore(java.io.File(files.sovereignDir, "sync"))
            .writeOutbound(SyncEngine(mailbox).buildOutbound())
    }
}

/**
 * Write a parsed EPUB's images into the sovereign store beside the book, returning the cover's
 * path if it has one.
 *
 * Only images the content actually references are kept, plus the cover — a publisher's archive
 * routinely carries fonts, stylesheets and unused artwork, and storing all of it would inflate
 * a library for no reading benefit. Failures are swallowed per file: a book that is missing one
 * plate should still open, and the kept `.epub` means every one of these is re-derivable.
 */
internal fun CitationRepository.storeBookImages(bookKey: String, parsed: EpubParser.ParsedEpub): String? {
    val referenced = parsed.book.chapters
        .flatMap { chapter -> chapter.blocks.filterIsInstance<DocumentBlock.Image>() }
        .map { it.src }
        .toSet()

    (referenced + listOfNotNull(parsed.coverPath)).forEach { src ->
        val data = parsed.resources[src] ?: return@forEach
        runCatching { files.writeBookAsset(bookKey, src, data) }
    }

    val cover = parsed.coverPath ?: return null
    val bytes = parsed.resources[cover] ?: return null
    return runCatching { files.writeCover(bookKey, bytes).absolutePath }.getOrNull()
}
