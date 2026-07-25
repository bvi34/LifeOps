package com.citation.app.data

import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.db.KeyWatermarkEntity
import com.citation.app.data.db.SyncStateEntity
import com.citation.app.data.rr.RoyalRoadClient
import com.citation.app.data.rr.RoyalRoadCoordinator
import com.citation.app.data.store.FileStores
import com.citation.core.epub.EpubParser
import com.citation.core.identity.IdentityKey
import com.citation.core.key.EntityType
import com.citation.core.key.KeyAllocator
import com.citation.core.model.Book
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import com.citation.core.store.Ownership
import com.citation.core.store.Store
import com.citation.core.sync.BookLifecycle
import com.citation.core.sync.Mailbox
import com.citation.core.sync.NotePacket
import com.citation.core.sync.UpPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single source of truth the reader's ViewModels talk to. It owns the offline key allocator and
 * the up-bound mailbox (both restored from the sovereign DB so minting and sync versions survive
 * restarts), and it drives the walking-skeleton path: EPUB bytes → [Book] → persisted → notes
 * captured against it → packets queued for LifeOps.
 *
 * Everything here is offline-first: importing, minting keys, and capturing notes never touch the
 * network. Sync is a separate, drain-the-outbox step.
 */
class CitationRepository private constructor(
    private val db: CitationDatabase,
    private val files: FileStores,
    private val keys: KeyAllocator,
    private val outbox: Mailbox<UpPacket, Nothing>,
    /** The Royal Road read loop (skim → buffer → cache → backfill → poll → evict). */
    val royalRoad: RoyalRoadCoordinator
) {

    val books: Flow<List<BookSummary>> =
        db.bookDao().observeAll().map { list ->
            list.map { BookSummary(it.key, it.title, it.author, it.readingState, it.acquisitionState) }
        }

    val notes: Flow<List<Note>> =
        db.noteDao().observeAll().map { list -> list.map(CitationMappers::noteFromEntity) }

    /**
     * Ingest an EPUB: parse to the internal model, mint a book key, persist metadata + chapters into
     * the sovereign store (EPUB is owned), and checkpoint the key watermark. Returns the keyed book,
     * or `null` if the archive yields no content.
     */
    suspend fun importEpub(bytes: ByteArray, now: Long = System.currentTimeMillis()): Book? {
        val parsed = EpubParser.parse(bytes) ?: return null
        val bookKey = keys.next(EntityType.BOOK)
        val book = parsed.book.copy(key = bookKey)
        val isbn = (parsed.identity.strongest as? IdentityKey.Isbn)?.normalized
        val descriptor = SourceDescriptor(
            bookKey = bookKey,
            sourceType = book.metadata.source,
            sourceId = isbn,
            title = book.metadata.title,
            author = book.metadata.author
        )
        // Owned content ⇒ chapters stored inline in the sovereign DB.
        val inline = Ownership.contentStore(book.metadata.source) == Store.SOVEREIGN

        db.bookDao().upsert(CitationMappers.bookToEntity(book, descriptor, BookLifecycle.owned(), now))
        db.chapterDao().upsertAll(
            book.chapters.map { CitationMappers.chapterToEntity(bookKey.toString(), it, inline, now) }
        )
        // Keep the raw file too, so re-parse / re-export stays possible.
        files.writeOwned(bookKey.toString(), "epub", bytes)
        checkpointKey(EntityType.BOOK)
        return book
    }

    /** Load a persisted book back into the internal model for the reader. */
    suspend fun loadBook(bookKey: String): Book? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val chapters = db.chapterDao().forBook(bookKey)
        return CitationMappers.bookFromEntities(entity, chapters)
    }

    /** Persist the reader's last position for restore-on-reopen. */
    suspend fun savePosition(bookKey: String, chapterOrdinal: Int, charOffset: Int) {
        db.bookDao().savePosition(bookKey, chapterOrdinal, charOffset)
    }

    /**
     * Capture a highlight over a flowing-text selection and attach a passage-anchored note, then
     * queue the note up the mailbox and persist everything (highlight, note, sync bookkeeping).
     */
    suspend fun captureNote(
        book: Book,
        chapterOrdinal: Int,
        selectionStart: Int,
        selectionEnd: Int,
        noteBody: String,
        now: Long = System.currentTimeMillis()
    ): Note {
        val bookKey = book.key!!
        val chapter = book.chapterAt(chapterOrdinal) ?: error("no chapter $chapterOrdinal")
        val descriptor = SourceDescriptor(
            bookKey = bookKey,
            sourceType = book.metadata.source,
            sourceId = null,
            title = book.metadata.title,
            author = book.metadata.author
        )
        val highlight = Highlight.captureFlowing(
            key = keys.next(EntityType.HIGHLIGHT),
            source = descriptor,
            chapterText = chapter.text,
            chapterOrdinal = chapterOrdinal,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            createdAt = now
        )
        val note = Note.anchored(keys.next(EntityType.NOTE), noteBody, highlight, now)

        val versioned = outbox.post(NotePacket.of(note))

        db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
        db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        checkpointKey(EntityType.HIGHLIGHT)
        checkpointKey(EntityType.NOTE)
        persistSyncState()
        return note
    }

    /** Outbound packets LifeOps hasn't acknowledged yet — what a sync pass would deliver. */
    fun pendingUpPackets(): List<UpPacket> = outbox.outboxSince(0).map { it.payload }

    private suspend fun checkpointKey(type: String) {
        db.syncStateDao().saveWatermark(KeyWatermarkEntity(type, keys.highWater(type)))
    }

    private suspend fun persistSyncState() {
        db.syncStateDao().saveSyncState(
            SyncStateEntity(0, outbox.currentOutVersion, outbox.inboxCursor)
        )
    }

    data class BookSummary(
        val key: String,
        val title: String,
        val author: String?,
        val readingState: String,
        val acquisitionState: String
    )

    companion object {
        /** Build the repository, restoring the key allocator and mailbox from persisted state. */
        suspend fun create(db: CitationDatabase, files: FileStores): CitationRepository {
            val watermarks = db.syncStateDao().watermarks().associate { it.type to it.highWater }
            val keys = KeyAllocator(seed = watermarks)
            val outbox = Mailbox<UpPacket, Nothing>()
            db.syncStateDao().syncState()?.let { outbox.restore(it.outVersion, it.inboxCursor) }
            val royalRoad = RoyalRoadCoordinator(db.royalRoadDao(), RoyalRoadClient(), files)
            return CitationRepository(db, files, keys, outbox, royalRoad)
        }
    }
}
