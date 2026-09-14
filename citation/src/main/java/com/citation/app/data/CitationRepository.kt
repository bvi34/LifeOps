package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.db.BlockCodec
import com.citation.app.data.db.BookmarkEntity
import com.citation.app.data.db.CollectionEntity
import com.citation.app.data.db.CollectionMemberEntity
import com.citation.app.data.db.KeyWatermarkEntity
import com.citation.app.data.db.SyncStateEntity
import com.citation.app.data.ao3.Ao3Client
import com.citation.app.data.db.OpdsCatalogEntity
import com.citation.app.data.db.ReaderSettingsCodec
import com.citation.app.data.db.ReaderSettingsEntity
import com.citation.app.data.db.ReadingPaceEntity
import com.citation.app.data.opds.CatalogCredentials
import com.citation.app.data.opds.OpdsClient
import com.citation.app.data.opds.map
import com.citation.app.data.rr.RoyalRoadClient
import com.citation.app.data.rr.RoyalRoadCoordinator
import com.citation.app.data.store.FileStores
import com.citation.core.anchor.TextAnchor
import com.citation.core.capture.CaptureBuilder
import com.citation.core.capture.CaptureClusterer
import com.citation.core.capture.CaptureLink
import com.citation.core.capture.CapturePromotion
import com.citation.core.capture.CaptureTriage
import com.citation.core.capture.ProvenanceLadder
import com.citation.core.capture.RawCapture
import com.citation.core.doc.DocumentBlock
import com.citation.core.epub.EpubParser
import com.citation.core.kindle.KindleNotebook
import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.key.KeyAllocator
import com.citation.core.library.BookCollection
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Bookmarks
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReadingPace
import com.citation.core.opds.CatalogPage
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import com.citation.core.opds.OpdsFeed
import com.citation.core.library.LibraryEntry
import com.citation.core.manifest.StorageInventory
import com.citation.core.manifest.StorageReport
import com.citation.core.model.Book
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import com.citation.core.speech.SavedPlace
import com.citation.core.note.Highlight
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.PassageReference
import com.citation.core.note.SourceDescriptor
import com.citation.core.pdf.PdfFlow
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
import com.citation.core.sync.TelemetryPacket
import com.citation.core.sync.SyncEngine
import com.citation.core.sync.UpPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The single source of truth the reader's ViewModels talk to. It owns the offline key allocator and
 * the up-bound mailbox (both restored from the sovereign DB so minting and sync versions survive
 * restarts), and it drives the walking-skeleton path: EPUB bytes → [Book] → persisted → notes
 * captured against it → packets queued for LifeOps.
 *
 * Everything here is offline-first: importing, minting keys, and capturing notes never touch the
 * network. Sync is a separate, drain-the-outbox step.
 *
 * **Where the rest of it is.** This file holds the collaborators, the library core — importing a
 * book, opening one, the sync round — and the factory that restores the allocator and mailbox. The
 * rest of what the repository can do lives beside it as extensions on this class, one file per
 * concern: `CitationPdf`, `CitationReadInPlace`, `CitationOreillyAccess`, `CitationCapture`,
 * `CitationBookmarks`, `CitationReaderSettings`, `CitationCatalogs`, `CitationCollections`, and
 * `CitationSupport` for the plumbing more than one of them needs. The types they all speak in are in
 * `CitationTypes`.
 *
 * Extensions rather than collaborator classes, because the constructor is private and the mailbox,
 * key allocator and outbox are one restored-together whole: handing each concern its own slice would
 * mean either splitting that state — which is what the private constructor exists to prevent — or
 * passing the repository into each, which buys indirection and no isolation. The cost is that the
 * collaborators are `internal` rather than `private`: visible inside `:citation`, and no further.
 */
class CitationRepository private constructor(
    internal val db: CitationDatabase,
    internal val files: FileStores,
    internal val keys: KeyAllocator,
    // The mailbox now carries both directions: up-packets out, acquire intents in.
    internal val mailbox: Mailbox<UpPacket, AcquireBookIntent>,
    /** The Royal Road read loop (skim → buffer → cache → backfill → poll → evict). */
    val royalRoad: RoyalRoadCoordinator,
    /** Downloads AO3's official EPUB export; AO3 works are ingested as owned EPUB snapshots. */
    internal val ao3Client: Ao3Client,
    /** Encrypted library card/PIN + proxy host for read-in-place O'Reilly (never synced). */
    internal val oreillyAccess: OreillyAccess,
    /** Pulls the text layer out of an owned PDF, for the reflow track. */
    internal val pdfText: PdfTextSource = PdfTextSource.NONE,
    /** Encrypted sign-ins for private OPDS servers (never synced, never in the database). */
    internal val catalogCredentials: CatalogCredentials? = null,
    /** Fetches catalog pages, covers and books. The only thing here that touches a catalog server. */
    internal val opdsClient: OpdsClient = OpdsClient(catalogCredentials)
) {

    val books: Flow<List<BookSummary>> =
        db.bookDao().observeAll().map { list ->
            list.map { CitationMappers.summaryFromEntity(it) }
        }

    /**
     * The shelf, as the pure library layer wants it: every book projected with its metadata and the
     * collections it sits on. Membership is joined here, once for the whole library, rather than
     * queried per book — a shelf of hundreds should cost two queries, not hundreds.
     */
    val library: Flow<List<LibraryEntry>> =
        combine(db.bookDao().observeAll(), db.collectionDao().observeMembers()) { books, members ->
            val byBook = members.groupBy({ it.bookKey }, { it.collectionId })
            books.map { CitationMappers.libraryEntry(it, byBook[it.key]?.toSet().orEmpty()) }
        }

    /** The user's shelves, in their chosen order. */
    val collections: Flow<List<BookCollection>> =
        db.collectionDao().observeAll().map { list ->
            list.map { BookCollection(id = it.id, name = it.name, position = it.position) }
        }

    /** The most recently opened book, for the Read tab's "pick up where you left off". */
    val lastOpened: Flow<BookSummary?> =
        db.bookDao().observeLastOpened().map { it?.let(CitationMappers::summaryFromEntity) }

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

        // Extract the cover and illustrations before persisting, so the row that says a book has a
        // cover is only written once the file behind it actually exists.
        val coverPath = storeBookImages(bookKey.toString(), parsed)

        db.bookDao().upsert(
            CitationMappers.bookToEntity(book, descriptor, BookLifecycle.owned(), now, coverPath)
        )
        db.chapterDao().upsertAll(
            book.chapters.map { CitationMappers.chapterToEntity(bookKey.toString(), it, inline, now) }
        )
        // Keep the raw file too, so re-parse / re-export stays possible.
        files.writeOwned(bookKey.toString(), "epub", bytes)
        checkpointKey(EntityType.BOOK)
        // A hard identity just appeared: adopt any provisional captures that were waiting for it.
        promoteCapturesTo(
            CapturePromotion.PromotableRecord(
                bookKey = bookKey,
                identity = isbn?.let { IdentitySet(IdentityKey.Isbn(it)) } ?: IdentitySet(emptyList()),
                title = book.metadata.title,
                author = book.metadata.author
            )
        )
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

    /** Stamp a book as just-opened so the Read tab resumes it. Called whenever any reader is entered. */
    suspend fun markOpened(bookKey: String, now: Long = System.currentTimeMillis()) {
        db.bookDao().touchOpened(bookKey, now)
    }

    /**
     * Open a library entry, routing a Royal Road serial through its own loader (chapter bodies live
     * in the disposable cache, not the `chapters` table) and every other source — including an AO3
     * work, whose EPUB snapshot stores chapters inline like any owned EPUB — through the plain loader.
     * The stored last position rides along so the ViewModel can restore chapter + scroll.
     */
    suspend fun openBook(bookKey: String): OpenResult? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val listening = entity.listenChapterOrdinal?.let { ordinal ->
            SavedPlace(ordinal, entity.listenCharOffset ?: 0, entity.listenedAt)
        }
        val book = when (entity.sourceType) {
            SourceType.ROYAL_ROAD.name -> {
                val fictionId = entity.sourceId?.toLongOrNull() ?: return null
                return OpenResult(
                    openRoyalRoad(fictionId), fictionId, entity.lastChapterOrdinal, entity.lastCharOffset,
                    entity.positionSavedAt, listening
                )
            }
            else -> CitationMappers.bookFromEntities(entity, db.chapterDao().forBook(bookKey))
        }
        return OpenResult(
            book, null, entity.lastChapterOrdinal, entity.lastCharOffset,
            entity.positionSavedAt, listening
        )
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
            promoteCapturesTo(
                CapturePromotion.PromotableRecord(
                    minted, IdentitySet(IdentityKey.RoyalRoadId(fictionId)),
                    book.metadata.title, book.metadata.author
                )
            )
            minted
        }
        // A serial's chapters live in the borrowed file store, not the chapters table, so its
        // progress denominator comes from the catalog the coordinator just loaded.
        db.bookDao().setChapterCount(key.toString(), book.chapters.size)
        return book.copy(key = key)
    }

    /**
     * Open an Archive of Our Own work. AO3 is ingested via its **official EPUB download** and kept as
     * an owned snapshot: if the work is already in the library (matched by its AO3 work id) it just
     * loads; otherwise it is downloaded and imported once. Returns the keyed [Book].
     *
     * Throws if the work has never been imported and the download/parse fails — the ViewModel turns
     * that into a user-facing "couldn't open" rather than a silent empty book.
     */
    suspend fun openAo3(workId: Long): Book {
        db.bookDao().findBySource(workId.toString(), SourceType.AO3.name)?.let { existing ->
            return CitationMappers.bookFromEntities(existing, db.chapterDao().forBook(existing.key))
        }
        return importAo3(workId) ?: error("Could not download or parse AO3 work $workId")
    }

    /**
     * Download AO3 work [workId]'s official EPUB, parse it, and persist it as an **owned** book keyed
     * by the AO3 work id — the same shape as [importEpub] (chapters stored inline in the sovereign DB,
     * the raw EPUB kept for re-parse/export), but tagged [SourceType.AO3] and carrying an
     * [IdentityKey.Ao3Id] so it dedups by work and files as *Fun* on the LifeOps side. Returns the
     * keyed [Book], or `null` if the archive yields no content.
     */
    suspend fun importAo3(workId: Long, now: Long = System.currentTimeMillis()): Book? {
        val bytes = ao3Client.downloadEpub(workId)
        val parsed = EpubParser.parse(bytes) ?: return null
        val bookKey = keys.next(EntityType.BOOK)
        // Re-tag the parsed EPUB as an AO3 source so it categorises + dedups as a work, not a plain EPUB.
        val book = parsed.book.copy(
            key = bookKey,
            metadata = parsed.book.metadata.copy(source = SourceType.AO3)
        )
        val descriptor = SourceDescriptor(
            bookKey = bookKey,
            sourceType = SourceType.AO3,
            sourceId = workId.toString(),
            title = book.metadata.title,
            author = book.metadata.author
        )
        val coverPath = storeBookImages(bookKey.toString(), parsed)
        db.bookDao().upsert(
            CitationMappers.bookToEntity(book, descriptor, BookLifecycle.owned(), now, coverPath)
        )
        db.chapterDao().upsertAll(
            book.chapters.map { CitationMappers.chapterToEntity(bookKey.toString(), it, storeInline = true, now) }
        )
        files.writeOwned(bookKey.toString(), "epub", bytes)
        checkpointKey(EntityType.BOOK)
        promoteCapturesTo(
            CapturePromotion.PromotableRecord(
                bookKey, IdentitySet(IdentityKey.Ao3Id(workId)),
                book.metadata.title, book.metadata.author
            )
        )
        return book
    }

    /**
     * Persist the reader's last position for restore-on-reopen, and — when the reader can measure
     * it — how far through the book that is.
     *
     * The fraction is passed in rather than computed here because measuring it in characters needs
     * every chapter's length, and the reader already has the book open. The library then reads back
     * the same number the page showed, instead of a chapter-count estimate that disagrees with it.
     */
    suspend fun savePosition(
        bookKey: String,
        chapterOrdinal: Int,
        charOffset: Int,
        progressFraction: Float? = null,
        now: Long = System.currentTimeMillis()
    ) {
        db.bookDao().savePosition(bookKey, chapterOrdinal, charOffset, now)
        progressFraction?.let { db.bookDao().saveProgress(bookKey, it.coerceIn(0f, 1f)) }
    }

    /**
     * Record where the **voice** got to, in canonical characters.
     *
     * Its own record rather than an overwrite of the reading position: listening happens with the
     * app off screen and routinely ends up further on than the last page anybody looked at, and the
     * book should open at whichever place was reached last. Progress rides along so the shelf shows
     * the ground a listener covered, the same as it does for reading.
     */
    suspend fun saveListeningPosition(
        bookKey: String,
        chapterOrdinal: Int,
        charOffset: Int,
        progressFraction: Float? = null,
        now: Long = System.currentTimeMillis()
    ) {
        db.bookDao().saveListeningPosition(bookKey, chapterOrdinal, charOffset, now)
        progressFraction?.let { db.bookDao().saveProgress(bookKey, it.coerceIn(0f, 1f)) }
    }

    /**
     * Remove a book from the library and reclaim everything it borrowed. Routes by source: a Royal
     * Road serial is un-favourited and its cache + catalog dropped via the coordinator; an owned
     * EPUB/PDF has its file and inline chapters removed. In every case the [BookEntity] itself is
     * deleted. Notes/highlights are deliberately left intact — they're sovereign and still hold their
     * frozen snapshots, matching how [deleteNote] treats deletion as local and non-destructive to the
     * captured record.
     */
    suspend fun deleteBook(bookKey: String) {
        val entity = db.bookDao().get(bookKey) ?: return
        when (entity.sourceType) {
            SourceType.ROYAL_ROAD.name ->
                entity.sourceId?.toLongOrNull()?.let { royalRoad.forget(it) }
            SourceType.PDF.name -> {
                files.deleteOwned(bookKey, "pdf")
                // Drops the reflowed text track with it (absent when the PDF was never reflowed).
                db.chapterDao().deleteForBook(bookKey)
            }
            // AO3 is an owned EPUB snapshot — its raw file + inline chapters are removed like an EPUB.
            SourceType.EPUB.name, SourceType.AO3.name -> {
                files.deleteOwned(bookKey, "epub")
                db.chapterDao().deleteForBook(bookKey)
            }
            else -> db.chapterDao().deleteForBook(bookKey)
        }
        // Extracted covers and illustrations go with the book; they are derived from the file that
        // was just removed, so nothing is left behind pointing at content that no longer exists.
        files.deleteBookAssets(bookKey)
        db.bookDao().delete(bookKey)
    }

    /**
     * Delete a note (and its passage highlight vanishes with it, since the reader renders highlights
     * from their notes). Local-only: LifeOps keeps the copy it already acked — a delete-tombstone on
     * the sync seam is deliberately out of scope, so this removes your local record, not the world's.
     */
    suspend fun deleteNote(noteKey: String) {
        db.noteDao().delete(noteKey)
    }

    @Volatile
    internal var openReaderBookKey: String? = null

    /** The settings every book follows unless it has its own. */
    val globalReaderSettings: Flow<ReaderSettings> =
        db.readerSettingsDao().observe(ReaderSettingsEntity.GLOBAL)
            .map { ReaderSettingsCodec.decode(it?.settingsJson) }
    /** Saved catalogs, in order, with whether a sign-in is on file for each. */
    val catalogs: Flow<List<CatalogSource>> =
        db.opdsCatalogDao().observeAll().map { list -> list.map { toSource(it) } }

    companion object {
        /** Build the repository, restoring the key allocator and mailbox from persisted state. */
        suspend fun create(
            db: CitationDatabase,
            files: FileStores,
            oreillyAccess: OreillyAccess,
            pdfText: PdfTextSource = PdfTextSource.NONE,
            catalogCredentials: CatalogCredentials? = null
        ): CitationRepository {
            val watermarks = db.syncStateDao().watermarks().associate { it.type to it.highWater }
            val keys = KeyAllocator(seed = watermarks)
            val mailbox = Mailbox<UpPacket, AcquireBookIntent>()
            db.syncStateDao().syncState()?.let { mailbox.restore(it.outVersion, it.inboxCursor) }
            // Rebuild the outbox from durable storage. Notes posted in past sessions still carry their
            // syncVersion in the notes table, but the in-memory outbox is empty after a relaunch — so
            // without this, notes captured before LifeOps could take them (or before the sync seam
            // existed) would never be resent. Cleared once LifeOps acks them (see sync()).
            val pending = db.noteDao().pendingSync().map { entity ->
                Mailbox.Versioned<UpPacket>(entity.syncVersion!!, NotePacket.of(CitationMappers.noteFromEntity(entity)))
            }
            mailbox.seedOutbox(pending)
            val royalRoad = RoyalRoadCoordinator(db.royalRoadDao(), RoyalRoadClient(), files)
            return CitationRepository(
                db, files, keys, mailbox, royalRoad, Ao3Client(), oreillyAccess, pdfText, catalogCredentials
            ).also { repo ->
                // Write the reseeded backlog to the file-drop now so LifeOps can pick it up without
                // waiting for the next capture or the periodic worker.
                if (pending.isNotEmpty()) repo.flushOutbox()
            }
        }
    }
}
