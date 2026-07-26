package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.db.KeyWatermarkEntity
import com.citation.app.data.db.SyncStateEntity
import com.citation.app.data.rr.RoyalRoadClient
import com.citation.app.data.rr.RoyalRoadCoordinator
import com.citation.app.data.store.FileStores
import com.citation.core.anchor.TextAnchor
import com.citation.core.epub.EpubParser
import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.key.KeyAllocator
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.PassageReference
import com.citation.core.note.SourceDescriptor
import com.citation.core.store.Ownership
import com.citation.core.store.Store
import com.citation.core.sync.AcquireBookIntent
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.BindOrCreate
import com.citation.core.sync.BookLifecycle
import com.citation.core.sync.FileEnvelopeStore
import com.citation.core.sync.IntentReconciler
import com.citation.core.sync.Mailbox
import com.citation.core.sync.NotePacket
import com.citation.core.sync.ReadingState
import com.citation.core.sync.SyncEngine
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
    // The mailbox now carries both directions: up-packets out, acquire intents in.
    private val mailbox: Mailbox<UpPacket, AcquireBookIntent>,
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

    /** The source kind of a stored book, so the UI can route to the right reader track. */
    suspend fun sourceTypeOf(bookKey: String): SourceType? =
        db.bookDao().get(bookKey)?.let { SourceType.valueOf(it.sourceType) }

    /** The result of opening a book from the library: the keyed [Book] and, if it's a serial, its id. */
    data class OpenResult(val book: Book, val rrFictionId: Long?)

    /**
     * Open a library entry, routing a Royal Road serial through its own loader (chapter bodies live
     * in the disposable cache, not the `chapters` table) and an owned book through the plain loader.
     */
    suspend fun openBook(bookKey: String): OpenResult? {
        val entity = db.bookDao().get(bookKey) ?: return null
        return if (entity.sourceType == SourceType.ROYAL_ROAD.name) {
            val fictionId = entity.sourceId?.toLongOrNull() ?: return null
            OpenResult(openRoyalRoad(fictionId), fictionId)
        } else {
            OpenResult(CitationMappers.bookFromEntities(entity, db.chapterDao().forBook(bookKey)), null)
        }
    }

    /**
     * Open a Royal Road serial and register it as a **sovereign** [BookEntity] (minting a book key on
     * first open), so notes/highlights on it are keyed and survive eviction of its borrowed chapter
     * bodies. Returns the keyed [Book]; its chapters come from the RR loader.
     */
    suspend fun openRoyalRoad(fictionId: Long, now: Long = System.currentTimeMillis()): Book {
        val book = royalRoad.openStory(fictionId)
        val fiction = db.royalRoadDao().fiction(fictionId)
        val key = fiction?.bookKey?.let { EntityKey.parse(it) } ?: run {
            val minted = keys.next(EntityType.BOOK)
            db.bookDao().upsert(
                BookEntity(
                    key = minted.toString(),
                    title = book.metadata.title,
                    author = book.metadata.author,
                    sourceType = SourceType.ROYAL_ROAD.name,
                    sourceId = fictionId.toString(),
                    language = null,
                    // Borrowed but readable: acquired on the acquisition axis, reading on the other.
                    acquisitionState = AcquisitionState.ACQUIRED.name,
                    readingState = ReadingState.READING.name,
                    createdAt = now
                )
            )
            db.royalRoadDao().setBookKey(fictionId, minted.toString())
            checkpointKey(EntityType.BOOK)
            minted
        }
        return book.copy(key = key)
    }

    /** Persist the reader's last position for restore-on-reopen. */
    suspend fun savePosition(bookKey: String, chapterOrdinal: Int, charOffset: Int) {
        db.bookDao().savePosition(bookKey, chapterOrdinal, charOffset)
    }

    // --- PDF (own render track) ---------------------------------------------------------------

    /** A PDF opened for the paged reader: the owned file on disk (rendered page-by-page, never reflowed). */
    data class PdfSession(val bookKey: String, val file: java.io.File)

    /**
     * Import a PDF into the **owned** (sovereign) store: hash it for identity, dedup against an
     * existing copy of the same file, persist the bytes, and register a [BookEntity]. Returns the
     * book key. PDFs render as pages (see [PdfSession]), so no flowing chapters are stored.
     */
    suspend fun importPdf(
        bytes: ByteArray,
        title: String,
        now: Long = System.currentTimeMillis()
    ): String {
        val sha = com.citation.core.pdf.PdfTrack.sha256(bytes)
        // PDF SHA is a strong positive: identical bytes ⇒ same file, so dedup rather than re-import.
        db.bookDao().findBySource(sha, SourceType.PDF.name)?.let { return it.key }

        val key = keys.next(EntityType.BOOK)
        files.writeOwned(key.toString(), "pdf", bytes)
        db.bookDao().upsert(
            BookEntity(
                key = key.toString(),
                title = title,
                author = null,
                sourceType = SourceType.PDF.name,
                sourceId = sha,
                language = null,
                acquisitionState = AcquisitionState.ACQUIRED.name,
                readingState = ReadingState.TO_READ.name,
                createdAt = now
            )
        )
        checkpointKey(EntityType.BOOK)
        return key.toString()
    }

    /** The owned PDF file for [bookKey], for the paged reader to render. */
    fun pdfSession(bookKey: String): PdfSession =
        PdfSession(bookKey, java.io.File(files.sovereignDir, "$bookKey.pdf"))

    /**
     * Capture a note on a PDF page. Anchored with a [TextAnchor.Pdf] (page + quads + quote); when the
     * caller has no glyph rectangles it passes an empty [quads] for a page-level anchor.
     */
    suspend fun capturePdfNote(
        bookKey: String,
        page: Int,
        quote: String,
        body: String,
        quads: List<TextAnchor.Quad> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): Note {
        val entity = db.bookDao().get(bookKey) ?: error("no book $bookKey")
        val descriptor = SourceDescriptor(
            bookKey = EntityKey.parse(bookKey),
            sourceType = SourceType.PDF,
            sourceId = entity.sourceId,
            title = entity.title,
            author = entity.author
        )
        val highlight = Highlight(
            key = keys.next(EntityType.HIGHLIGHT),
            source = descriptor,
            quotedSnapshot = quote,
            anchor = com.citation.core.pdf.PdfTrack.anchor(page, quote, quads),
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

    // --- O'Reilly (read-in-place) -------------------------------------------------------------

    /** A read-in-place session: the deep link to open in O'Reilly's own reader, and the book id. */
    data class OreillySession(val bookKey: String, val bookId: String, val deepLink: String)

    /**
     * Register an O'Reilly book for read-in-place. **No content is cached** — it's licensed — only a
     * sovereign [BookEntity] holding your layer (id, title, last position, notes). Deduped by book id.
     */
    suspend fun addOreillyBook(
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

    /** Build the deep link that lands you at your saved O'Reilly position in one tap (else the cover). */
    suspend fun oreillySession(bookKey: String): OreillySession? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val bookId = entity.sourceId ?: return null
        val link = com.citation.core.oreilly.OreillyLink.deepLink(bookId, entity.externalLocation)
        return OreillySession(bookKey, bookId, link)
    }

    /** Persist the O'Reilly reader's last position token so reopening lands at your spot. */
    suspend fun saveExternalPosition(bookKey: String, location: String) {
        db.bookDao().saveExternalLocation(bookKey, location)
    }

    /**
     * Capture a note on a read-in-place source (O'Reilly): your layer only — a frozen quote plus the
     * reader's location token as an [TextAnchor.External] anchor. Content stays theirs; the note is
     * yours and sovereign.
     */
    suspend fun captureExternalNote(
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
    suspend fun captureNote(
        book: Book,
        chapterOrdinal: Int,
        selectionStart: Int,
        selectionEnd: Int,
        noteBody: String,
        now: Long = System.currentTimeMillis()
    ): Note {
        val chapter = book.chapterAt(chapterOrdinal) ?: error("no chapter $chapterOrdinal")
        val descriptor = descriptorFor(book)
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

        val versioned = mailbox.post(NotePacket.of(note))

        db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
        db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        checkpointKey(EntityType.HIGHLIGHT)
        checkpointKey(EntityType.NOTE)
        persistSyncState()
        return note
    }

    /**
     * Capture a **freestanding synthesis** note — your own artifact that may cite several passages.
     * Each quote is located in the book and anchored; quotes that aren't found are dropped (the
     * synthesis still stands on its own text). The note is queued and persisted like any other.
     */
    suspend fun captureSynthesis(
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

    /**
     * Resolve a note's references against the current state of its source, yielding each reference's
     * degradation state (resolved / fuzzy / orphaned / source-unavailable) and jump target. This is
     * how the Notes UI shows whether a note can still jump to live context or only display its frozen
     * snapshot.
     */
    suspend fun resolveNote(note: Note): List<NoteResolver.RefResolution> {
        // Pre-load the chapter texts the note's anchors need, keyed by chapter ordinal.
        val bookKey = note.source.bookKey?.toString()
        val entity = bookKey?.let { db.bookDao().get(it) }
        val textByOrdinal: Map<Int, String> = when {
            entity == null -> emptyMap()
            entity.sourceType == SourceType.ROYAL_ROAD.name -> {
                val fictionId = entity.sourceId ?: ""
                note.references.mapNotNull { (it.anchor as? TextAnchor.Flowing)?.chapterOrdinal }
                    .distinct()
                    .mapNotNull { ord -> files.readBorrowedChapter(fictionId, ord)?.let { ord to it } }
                    .toMap()
            }
            else -> db.chapterDao().forBook(entity.key)
                .mapNotNull { ch -> ch.text?.let { ch.ordinal to it } }.toMap()
        }
        return NoteResolver.resolveNote(note) { anchor ->
            (anchor as? TextAnchor.Flowing)?.let { textByOrdinal[it.chapterOrdinal] }
        }
    }

    /** Outbound packets LifeOps hasn't acknowledged yet — what a sync pass would deliver. */
    fun pendingUpPackets(): List<UpPacket> = mailbox.outboxSince(0).map { it.payload }

    /**
     * Run one sync round with LifeOps over the file-drop mailbox: write our unacked packets up,
     * then apply LifeOps' response — prune the acked packets and reconcile each acquire intent via
     * **bind-or-create** (matching an existing book, or minting a new `wanted` one). Offline-safe:
     * with no response file yet, it just leaves the outbound envelope for LifeOps to pick up.
     */
    suspend fun sync(now: Long = System.currentTimeMillis()): SyncSummary {
        val engine = SyncEngine(mailbox)
        val store = FileEnvelopeStore(java.io.File(files.sovereignDir, "sync"))
        val sent = mailbox.outboxSince(0).size
        store.writeOutbound(engine.buildOutbound())

        val inbound = store.readInbound() ?: return SyncSummary(sent, intentsCreated = 0, intentsKnown = 0)

        // Candidates for bind-or-create, snapshotted before applying (reconcile runs synchronously).
        val candidates = db.bookDao().getAll().map(::toCandidate)
        val toCreate = ArrayList<AcquireBookIntent>()
        var known = 0
        engine.applyInbound(inbound) { intent ->
            when (IntentReconciler.reconcile(intent, candidates)) {
                is IntentReconciler.Outcome.AlreadyKnown -> known++
                is IntentReconciler.Outcome.CreateWanted -> toCreate.add(intent)
            }
        }
        // Persist newly-wanted books (a fuzzy, center-authored placeholder on both state machines).
        toCreate.forEach { intent ->
            val key = keys.next(EntityType.BOOK)
            val lifecycle = BookLifecycle.wanted()
            db.bookDao().upsert(
                BookEntity(
                    key = key.toString(),
                    title = intent.title,
                    author = intent.author,
                    sourceType = SourceType.INTERNAL.name,
                    sourceId = null,
                    language = null,
                    acquisitionState = lifecycle.acquisition.name,
                    readingState = lifecycle.reading.name,
                    createdAt = now
                )
            )
        }
        checkpointKey(EntityType.BOOK)
        persistSyncState()
        return SyncSummary(sent, intentsCreated = toCreate.size, intentsKnown = known)
    }

    /** Build a bind-or-create candidate from a stored book, recovering its identity by source type. */
    private fun toCandidate(e: BookEntity): BindOrCreate.Candidate {
        val identity = when (e.sourceType) {
            SourceType.EPUB.name -> e.sourceId?.let { IdentitySet(IdentityKey.Isbn(it)) }
            SourceType.PDF.name -> e.sourceId?.let { IdentitySet(IdentityKey.PdfSha(it)) }
            SourceType.ROYAL_ROAD.name ->
                e.sourceId?.toLongOrNull()?.let { IdentitySet(IdentityKey.RoyalRoadId(it)) }
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

    /** Outcome of a sync round, for status display. */
    data class SyncSummary(val sent: Int, val intentsCreated: Int, val intentsKnown: Int)

    /** Build a note's frozen source descriptor from the book's stored identity (title/author/id). */
    private suspend fun descriptorFor(book: Book): SourceDescriptor {
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
    private fun anchorQuoteInBook(book: Book, quote: String): PassageReference? {
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

    private suspend fun checkpointKey(type: String) {
        db.syncStateDao().saveWatermark(KeyWatermarkEntity(type, keys.highWater(type)))
    }

    private suspend fun persistSyncState() {
        db.syncStateDao().saveSyncState(
            SyncStateEntity(0, mailbox.currentOutVersion, mailbox.inboxCursor)
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
            val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
            db.syncStateDao().syncState()?.let { mailbox.restore(it.outVersion, it.inboxCursor) }
            val royalRoad = RoyalRoadCoordinator(db.royalRoadDao(), RoyalRoadClient(), files)
            return CitationRepository(db, files, keys, mailbox, royalRoad)
        }
    }
}
