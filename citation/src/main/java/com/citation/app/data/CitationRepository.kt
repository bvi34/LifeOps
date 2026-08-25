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
import com.citation.core.note.Highlight
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
 */
class CitationRepository private constructor(
    private val db: CitationDatabase,
    private val files: FileStores,
    private val keys: KeyAllocator,
    // The mailbox now carries both directions: up-packets out, acquire intents in.
    private val mailbox: Mailbox<UpPacket, AcquireBookIntent>,
    /** The Royal Road read loop (skim → buffer → cache → backfill → poll → evict). */
    val royalRoad: RoyalRoadCoordinator,
    /** Downloads AO3's official EPUB export; AO3 works are ingested as owned EPUB snapshots. */
    private val ao3Client: Ao3Client,
    /** Encrypted library card/PIN + proxy host for read-in-place O'Reilly (never synced). */
    private val oreillyAccess: OreillyAccess,
    /** Pulls the text layer out of an owned PDF, for the reflow track. */
    private val pdfText: PdfTextSource = PdfTextSource.NONE,
    /** Encrypted sign-ins for private OPDS servers (never synced, never in the database). */
    private val catalogCredentials: CatalogCredentials? = null,
    /** Fetches catalog pages, covers and books. The only thing here that touches a catalog server. */
    private val opdsClient: OpdsClient = OpdsClient(catalogCredentials)
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
     * The result of opening a book from the library: the keyed [Book], its serial id if borrowed, and
     * the reader's **saved position** so a reopen lands where you left off rather than at chapter one.
     */
    data class OpenResult(
        val book: Book,
        val rrFictionId: Long?,
        val chapterOrdinal: Int = 0,
        val charOffset: Int = 0
    )

    /**
     * Open a library entry, routing a Royal Road serial through its own loader (chapter bodies live
     * in the disposable cache, not the `chapters` table) and every other source — including an AO3
     * work, whose EPUB snapshot stores chapters inline like any owned EPUB — through the plain loader.
     * The stored last position rides along so the ViewModel can restore chapter + scroll.
     */
    suspend fun openBook(bookKey: String): OpenResult? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val book = when (entity.sourceType) {
            SourceType.ROYAL_ROAD.name -> {
                val fictionId = entity.sourceId?.toLongOrNull() ?: return null
                return OpenResult(openRoyalRoad(fictionId), fictionId, entity.lastChapterOrdinal, entity.lastCharOffset)
            }
            else -> CitationMappers.bookFromEntities(entity, db.chapterDao().forBook(bookKey))
        }
        return OpenResult(book, null, entity.lastChapterOrdinal, entity.lastCharOffset)
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
        progressFraction: Float? = null
    ) {
        db.bookDao().savePosition(bookKey, chapterOrdinal, charOffset)
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
        promoteCapturesTo(
            CapturePromotion.PromotableRecord(key, IdentitySet(IdentityKey.PdfSha(sha)), title, null)
        )
        return key.toString()
    }

    /** The owned PDF file for [bookKey], for the paged reader to render. */
    fun pdfSession(bookKey: String): PdfSession =
        PdfSession(bookKey, java.io.File(files.sovereignDir, "$bookKey.pdf"))

    // --- PDF reflow (the second track over the same file) ---------------------------------------

    /**
     * Pulls the text layer out of an owned PDF, one page at a time. Implemented in the Android layer
     * (PDFBox); a seam rather than a direct call so the repository stays testable without a device
     * and so a different extractor can be dropped in later.
     */
    fun interface PdfTextSource {
        suspend fun pages(file: java.io.File): List<String>

        companion object {
            /** No extractor wired: every PDF stays paged-only. */
            val NONE = PdfTextSource { emptyList() }
        }
    }

    /** What [reflowPdf] managed to do — the three outcomes the UI has to say something about. */
    sealed interface ReflowResult {
        /** The PDF now has a flowing track of [chapters] chapters over [pages] pages. */
        data class Reflowed(val chapters: Int, val pages: Int) : ReflowResult
        /** A scan (or an image-only PDF): no text layer to reflow, so it stays paged-only. */
        data object NoTextLayer : ReflowResult
        /** The file couldn't be read at all (missing, corrupt, or no extractor wired). */
        data object Unreadable : ReflowResult
    }

    /**
     * Build the **reflowed text track** for an imported PDF: extract its pages, reflow them into the
     * format-blind [Book] the flowing reader already renders, and store the chapters inline against
     * the existing book record.
     *
     * The PDF file itself is untouched and the paged track stays available — this *adds* a way to
     * read the same book, it doesn't convert it. That matters because reflow is lossy (columns,
     * tables, equations), so the rendered page remains the ground truth you can always fall back to.
     * Re-running it replaces the stored chapters, so a bad reflow can simply be rebuilt.
     *
     * Notes already captured are unaffected: they are anchored by page + quote, not by chapter.
     */
    suspend fun reflowPdf(bookKey: String, now: Long = System.currentTimeMillis()): ReflowResult {
        val entity = db.bookDao().get(bookKey) ?: return ReflowResult.Unreadable
        if (entity.sourceType != SourceType.PDF.name) return ReflowResult.Unreadable
        val file = java.io.File(files.sovereignDir, "$bookKey.pdf")
        if (!file.exists()) return ReflowResult.Unreadable

        val pages = pdfText.pages(file)
        if (pages.isEmpty()) return ReflowResult.Unreadable
        val book = PdfFlow.build(pages, entity.title, entity.author)
            ?: return ReflowResult.NoTextLayer

        // Replace any earlier reflow wholesale — chapter ordinals are positional, so a rebuild with
        // fewer chapters must not leave the tail of the old one behind.
        db.chapterDao().deleteForBook(bookKey)
        db.chapterDao().upsertAll(
            book.chapters.map { CitationMappers.chapterToEntity(bookKey, it, storeInline = true, now = now) }
        )
        // The reflow just created the chapter track this book's progress is measured against.
        db.bookDao().recountChapters(bookKey)
        return ReflowResult.Reflowed(chapters = book.chapters.size, pages = pages.size)
    }

    /** Whether [bookKey] already has a reflowed text track stored (so the reader can offer it). */
    suspend fun hasPdfFlow(bookKey: String): Boolean =
        db.chapterDao().cachedOrdinals(bookKey).isNotEmpty()

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

    // --- Read-in-place capture context -------------------------------------------------------
    // The book currently open in a read-in-place reader (O'Reilly), or null when none is open. It is
    // process-wide because the sanctioned capture entry point ([CaptureActivity]) is a *separate*
    // activity from the reader: when you select a passage in the hosted WebView and tap the system
    // "Save to Citation" item, that capture arrives labelled with our **own** container package
    // (there is no other app to name — the reader is ours), so without this the ladder would file it
    // under the container's app name ("Operations Sandbox") and lose the book you were reading. The
    // reader stamps this on open and clears it on close, so a self-originated capture can instead be
    // filed against the book actually on screen. Only combined with a self-origin check is it used, so
    // a stale value can never misattribute a capture that came from a genuinely different app.

    @Volatile
    private var openReaderBookKey: String? = null

    /** The reader marks a read-in-place book open, so a self-capture can be filed against it. */
    fun beginReaderContext(bookKey: String) { openReaderBookKey = bookKey }

    /** The reader marks its book closed. No-ops if a different book has since opened. */
    fun endReaderContext(bookKey: String) {
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
    suspend fun captureInOpenReader(
        quote: String,
        annotation: String = "",
        now: Long = System.currentTimeMillis()
    ): Note? {
        val bookKey = openReaderBookKey ?: return null
        val entity = db.bookDao().get(bookKey) ?: return null
        return captureExternalNote(bookKey, entity.externalLocation.orEmpty(), quote, annotation, now)
    }

    // --- O'Reilly (read-in-place) -------------------------------------------------------------

    /**
     * A read-in-place session: the deep link to open in O'Reilly's own reader (routed through your
     * library proxy when one is configured), the book id, and — when you've saved a card/PIN — the
     * credentials the reader uses to auto-reauth the library sign-in. [login] is null when no
     * credentials are stored, in which case you sign in by hand as before.
     */
    data class OreillySession(
        val bookKey: String,
        val bookId: String,
        val deepLink: String,
        val login: OreillyAccess.Credentials? = null,
        /** True when the warm page cache has gone stale (TTL) and should be dropped on open. */
        val purgeWarmCache: Boolean = false
    )

    /**
     * A **browse** session: the O'Reilly catalog opened through your library proxy (so the whole skim
     * runs on a library card), plus the card/PIN the WebView uses to auto-reauth the library sign-in —
     * exactly like the reader. This is the O'Reilly equivalent of Browse Royal Road: you find a book by
     * skimming O'Reilly's own catalog, and tapping into one hands it back to be opened read-in-place.
     */
    data class OreillyCatalog(
        val startUrl: String,
        val login: OreillyAccess.Credentials? = null
    )

    /** Build a browse session at O'Reilly's catalog, proxied through your library with your card/PIN. */
    fun oreillyCatalog(): OreillyCatalog =
        OreillyCatalog(
            startUrl = com.citation.core.oreilly.OreillyLink.browseUrl(oreillyAccess.proxy()),
            login = oreillyAccess.credentials()
        )

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

    /**
     * Build the deep link that lands you at your saved O'Reilly position in one tap (else the cover).
     * Routes through your configured library proxy so you reach the content on a library card, and
     * attaches your stored card/PIN for auto-reauth when the proxy session lapses.
     */
    suspend fun oreillySession(bookKey: String): OreillySession? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val bookId = entity.sourceId ?: return null
        val link = com.citation.core.oreilly.OreillyLink.deepLink(
            bookId, entity.externalLocation, oreillyAccess.proxy()
        )
        return OreillySession(bookKey, bookId, link, oreillyAccess.credentials())
    }

    // --- Kindle (read-in-place on read.amazon.com) --------------------------------------------

    /**
     * A Kindle **browse** session: your own library on `read.amazon.com`, opened so you can *pick* a
     * book instead of typing its ASIN + title by hand. The read-in-place counterpart to [OreillyCatalog]
     * — but there's no library proxy or stored credential here (Amazon keeps you signed in via cookies),
     * so the session is just the shelf's URL. Tapping a book hands its ASIN + learned title back to be
     * registered and opened.
     */
    data class KindleLibrary(val startUrl: String)

    /** Build a browse session at your Kindle library (the Cloud Reader's book grid). */
    fun kindleLibrary(): KindleLibrary =
        KindleLibrary(startUrl = com.citation.core.kindle.KindleLink.libraryUrl())

    /**
     * A Kindle read-in-place session: the ASIN and the `read.amazon.com` URL to open in Amazon's own
     * Cloud Reader. Unlike O'Reilly there's no library proxy or stored credential — you sign in to
     * Amazon in the WebView and cookies persist the session. Whispersync resumes your position on open,
     * so the URL is the ASIN alone.
     */
    data class KindleSession(
        val bookKey: String,
        val asin: String,
        val readerUrl: String
    )

    /**
     * Register a Kindle book for read-in-place. **No content is cached** — it's licensed — only a
     * sovereign [BookEntity] holding your layer (ASIN, title, last position label, notes). Deduped by
     * ASIN, exactly like [addOreillyBook].
     */
    suspend fun addKindleBook(
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
    suspend fun kindleSession(bookKey: String): KindleSession? {
        val entity = db.bookDao().get(bookKey) ?: return null
        val asin = entity.sourceId ?: return null
        return KindleSession(bookKey, asin, com.citation.core.kindle.KindleLink.readerUrl(asin))
    }

    // --- O'Reilly library access (encrypted card/PIN + proxy host, never synced) ----------------

    /** Current O'Reilly access config for Settings — proxy host and whether a card/PIN are on file. */
    fun oreillyAccessConfig(): OreillyAccess.Config = oreillyAccess.config()

    /** Save the library proxy host (blank restores the default). */
    fun setOreillyProxyHost(host: String) {
        oreillyAccess.setProxyHost(host.ifBlank { com.citation.core.oreilly.OreillyLibraryProxy.MID_CONTINENT_HOST })
    }

    /** Save (encrypted) your library card + PIN for auto-reauth. */
    fun setOreillyCredentials(card: String, pin: String) = oreillyAccess.setCredentials(card, pin)

    /** Forget the stored card + PIN (the proxy host stays). */
    fun clearOreillyCredentials() = oreillyAccess.clearCredentials()

    /**
     * Whether the warm page cache for [bookKey] has gone stale (untouched past the TTL) and should be
     * dropped. Read the last-open time *before* [markOpened] restamps it, so the decision reflects how
     * long the cache has actually sat. A never-opened book is never stale.
     */
    suspend fun oreillyWarmCacheStale(bookKey: String, now: Long = System.currentTimeMillis()): Boolean {
        val entity = db.bookDao().get(bookKey) ?: return false
        return com.citation.core.oreilly.OreillyCachePolicy.shouldPurge(
            lastWarmedAt = entity.lastOpenedAt, isReaderOpen = false, now = now
        )
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
        val highlight = highlightFor(book, chapter, chapterOrdinal, selectionStart, selectionEnd, descriptor, now)
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
    private fun highlightFor(
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

    // --- Cross-app capture (PROCESS_TEXT / share target / floating bubble / Kindle import) ---------

    /**
     * File a **quoted capture** handed in from another app — a browser selection, a shared passage, a
     * Kindle highlight. The [raw] material is run through the [ProvenanceLadder] to attach the best
     * identifier available (never unassigned), then frozen as a [SourceType.CAPTURE] note anchored by
     * an opaque location token. [annotation] is your optional note *about* the quote (blank ⇒ a plain
     * saved highlight). Fully offline; queued up the mailbox like any other note.
     */
    suspend fun captureQuoted(
        raw: RawCapture,
        quote: String,
        annotation: String = "",
        location: String? = raw.location ?: raw.url,
        now: Long = System.currentTimeMillis()
    ): Note {
        val provenance = ProvenanceLadder.resolve(raw)
        val highlight = CaptureBuilder.highlight(keys.next(EntityType.HIGHLIGHT), provenance, quote, location, now)
        val note = CaptureBuilder.quotedNote(keys.next(EntityType.NOTE), highlight, annotation, now)
        persistCapture(highlight, note)
        return note
    }

    /**
     * File a **manual quick-capture** — text you typed over some app via the floating bubble, with
     * nothing on screen to quote. Lands as a freestanding note whose provenance is whatever the ladder
     * could attach (often just the app package or a timestamp — i.e. thin, and headed for triage).
     */
    suspend fun captureManual(raw: RawCapture, now: Long = System.currentTimeMillis()): Note {
        val provenance = ProvenanceLadder.resolve(raw)
        val note = CaptureBuilder.manualNote(keys.next(EntityType.NOTE), provenance, raw.text, now)
        val versioned = mailbox.post(NotePacket.of(note))
        db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        checkpointKey(EntityType.NOTE)
        persistSyncState()
        return note
    }

    /**
     * Import a Kindle **notebook export** (HTML). Each highlight/note becomes a provisional capture on
     * the book's title cluster (the export carries no ISBN), so they behave like any other capture and
     * get promoted to the real book when you add it properly. Non-realtime and bounded by Amazon's
     * per-book clipping limit — this ingests exactly what the file contains. Returns the count filed,
     * or `null` if the HTML isn't a Kindle notebook.
     */
    suspend fun importKindleNotebook(html: String, now: Long = System.currentTimeMillis()): Int? {
        val export = KindleNotebook.parse(html) ?: return null
        val provenance = export.provenance(now)
        var filed = 0
        export.entries.forEach { entry ->
            if (entry.isStandaloneNote) {
                val note = CaptureBuilder.manualNote(keys.next(EntityType.NOTE), provenance, entry.annotation.orEmpty(), now)
                val versioned = mailbox.post(NotePacket.of(note))
                db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
            } else {
                val highlight = CaptureBuilder.highlight(
                    keys.next(EntityType.HIGHLIGHT), provenance, entry.quote, entry.locationToken, now
                )
                val note = CaptureBuilder.quotedNote(keys.next(EntityType.NOTE), highlight, entry.annotation.orEmpty(), now)
                persistCapture(highlight, note)
            }
            filed++
        }
        checkpointKey(EntityType.NOTE)
        checkpointKey(EntityType.HIGHLIGHT)
        persistSyncState()
        return filed
    }

    /**
     * Edit a note's body — the way you **add your own words onto a captured quote**. A capture lands
     * with an empty body (just the quote + its source); this lets you annotate it afterward. The
     * edited note is re-posted up the mailbox so LifeOps sees the new text. Returns the updated note,
     * or `null` if the key is unknown.
     */
    suspend fun editNoteBody(noteKey: String, body: String): Note? {
        val entity = db.noteDao().get(noteKey) ?: return null
        val updated = CitationMappers.noteFromEntity(entity).copy(body = body)
        val versioned = mailbox.post(NotePacket.of(updated))
        db.noteDao().upsert(CitationMappers.noteToEntity(updated, versioned.version))
        persistSyncState()
        return updated
    }

    /**
     * Set a note's tags — the local organizational layer. Unlike [editNoteBody] this does **not**
     * re-post up the mailbox: tags never travel on the sync wire (a note's *text* is the shared
     * artifact; its filing is Citation's own), so we persist locally and preserve the existing
     * `syncVersion` untouched. [tags] is stored as-is — callers normalize via `Tags.parse` first.
     */
    suspend fun setNoteTags(noteKey: String, tags: List<String>): Note? {
        val entity = db.noteDao().get(noteKey) ?: return null
        val updated = CitationMappers.noteFromEntity(entity).copy(tags = tags)
        db.noteDao().upsert(CitationMappers.noteToEntity(updated, entity.syncVersion))
        return updated
    }

    /**
     * Report **engaged** reading time for a book up the mailbox as a [TelemetryPacket]. The engaged
     * minutes are measured by the reader's [com.citation.core.reader.ReadingMeter] (idle dwell already
     * excluded), so LifeOps receives honest time. The packet carries the book's `sourceType` so
     * LifeOps can map it to a category (O'Reilly → Learning, Royal Road → Fun) without holding the
     * book. No-op for a zero-minute or unknown book.
     */
    suspend fun recordReadingTelemetry(
        bookKey: String,
        minutes: Int,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        if (minutes <= 0) return
        val entity = db.bookDao().get(bookKey) ?: return
        val packet = TelemetryPacket(
            bookKey = EntityKey.parse(bookKey),
            sourceType = SourceType.valueOf(entity.sourceType),
            title = entity.title,
            minutesRead = minutes,
            occurredAt = occurredAt,
            // Carry the source's own id (O'Reilly product id / ISBN / RR id) so LifeOps keeps the
            // book's real identity even from a read-only session with no notes.
            sourceId = entity.sourceId
        )
        mailbox.post(packet)
        persistSyncState()
    }

    /** Persist a captured highlight + its note and queue the note up the mailbox. */
    private suspend fun persistCapture(highlight: Highlight, note: Note) {
        val versioned = mailbox.post(NotePacket.of(note))
        db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
        db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        checkpointKey(EntityType.HIGHLIGHT)
        checkpointKey(EntityType.NOTE)
        persistSyncState()
    }

    /** All provisional-source clusters over captured notes — the retroactive grouping view. */
    suspend fun provisionalSources(): List<CaptureClusterer.ProvisionalSource> =
        CaptureClusterer.clusterNotes(db.noteDao().captures().map(CitationMappers::noteFromEntity))

    /** The thin-context triage queue: captures whose best identifier is only an app name/timestamp. */
    suspend fun triageQueue(): List<CaptureClusterer.ProvisionalSource> =
        CaptureTriage.queue(db.noteDao().captures().map(CitationMappers::noteFromEntity))

    /**
     * When a real book record appears, bind any provisional capture clusters it matches to it —
     * **promotion**. Re-points each promoted note's frozen descriptor at [bookKey] and re-posts it so
     * LifeOps sees the binding. Returns the number of notes promoted.
     */
    private suspend fun promoteCapturesTo(record: CapturePromotion.PromotableRecord): Int {
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

    /**
     * **Manually link** a capture to a book you already have — the user-driven counterpart to
     * [promoteCapturesTo]. When a capture never got a hard identity (a browser clip, a floating thought,
     * a Kindle-notebook highlight), promotion can't claim it; this is how you say "this *is* from that
     * book on my shelf." Binds every still-provisional note in [noteKey]'s cluster to [bookKey] — so
     * linking one member captures the whole provisional source, exactly as automatic promotion would —
     * re-posts each up the mailbox so LifeOps sees the binding, and returns the number of notes linked.
     * No fuzzy matching: the user has chosen the book. Returns 0 if the note or book is unknown, or the
     * note carries no cluster id to link.
     */
    suspend fun linkNoteToBook(noteKey: String, bookKey: String): Int {
        db.bookDao().get(bookKey) ?: return 0
        val entity = db.noteDao().get(noteKey) ?: return 0
        val clusterId = CitationMappers.noteFromEntity(entity).source.sourceId ?: return 0
        val captures = db.noteDao().captures().map(CitationMappers::noteFromEntity)
        val rebound = CaptureLink.link(captures, clusterId, EntityKey.parse(bookKey))
        rebound.forEach { note ->
            val versioned = mailbox.post(NotePacket.of(note))
            db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        }
        if (rebound.isNotEmpty()) persistSyncState()
        return rebound.size
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
     * Build the storage picture: per-item footprints tagged by recoverability (borrowed serials
     * read as reclaimable, owned files + notes as irreplaceable). **Visibility only** — this reports,
     * it never prunes.
     */
    suspend fun storageReport(): StorageReport {
        val items = ArrayList<StorageInventory.StorageItem>()
        db.bookDao().getAll().forEach { b ->
            val bytes = when (b.sourceType) {
                SourceType.PDF.name -> files.ownedFileSize(b.key, "pdf")
                // AO3 keeps its downloaded EPUB in the owned store, sized like any EPUB.
                SourceType.EPUB.name, SourceType.AO3.name -> files.ownedFileSize(b.key, "epub")
                SourceType.ROYAL_ROAD.name -> b.sourceId?.let { files.borrowedTotalSize(it) } ?: 0L
                else -> 0L // O'Reilly caches nothing; INTERNAL "wanted" has no content yet
            }
            if (bytes > 0) {
                items.add(
                    StorageInventory.StorageItem(
                        label = "${b.title} (${b.sourceType.lowercase()})",
                        sourceType = SourceType.valueOf(b.sourceType),
                        bytes = bytes
                    )
                )
            }
        }
        // All notes/highlights are sovereign and irreplaceable — surfaced as one line.
        val noteBytes = db.noteDao().getAllSync().sumOf {
            (it.body.length + it.frozenTitle.length + it.referencesJson.length).toLong()
        }
        if (noteBytes > 0) {
            items.add(StorageInventory.StorageItem("Notes & highlights", SourceType.INTERNAL, noteBytes))
        }
        return StorageInventory.report(items)
    }

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
        // LifeOps has durably taken every note up to its ack; drop their outbox marker so a later
        // relaunch doesn't re-seed and resend them (mirrors the mailbox prune applyInbound just did).
        if (inbound.ackedPacketVersion > 0) db.noteDao().clearSyncVersionThrough(inbound.ackedPacketVersion)
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
    private fun flushOutbox() {
        runCatching {
            FileEnvelopeStore(java.io.File(files.sovereignDir, "sync"))
                .writeOutbound(SyncEngine(mailbox).buildOutbound())
        }
    }

    // --- Bookmarks ------------------------------------------------------------------------------
    //
    // Sovereign: a bookmark is something the reader made. It survives the book being removed (the
    // row nulls rather than cascades) and stays legible from its frozen line, exactly as a note
    // does — degraded, not lost.

    /** Every bookmark in a book, in reading order. */
    fun bookmarks(bookKey: String): Flow<List<Bookmark>> =
        db.bookmarkDao().observeForBook(bookKey).map { rows -> rows.map { it.toBookmark() } }

    private fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
        key = EntityKey.parse(key)!!,
        bookKey = bookKey?.let { EntityKey.parse(it) },
        chapterOrdinal = chapterOrdinal,
        charOffset = charOffset,
        snippet = snippet,
        chapterTitle = chapterTitle,
        label = label,
        createdAt = createdAt
    )

    /**
     * Save a place, or return the one already saved there.
     *
     * Setting a bookmark twice on the same page means moving its label, not making a second mark,
     * so a position within a screenful of an existing bookmark reuses it. That is what lets one
     * control in the reader be a toggle rather than a way to accumulate near-identical rows.
     */
    suspend fun addBookmark(
        book: Book,
        chapterOrdinal: Int,
        charOffset: Int,
        label: String? = null,
        now: Long = System.currentTimeMillis()
    ): Bookmark {
        val bookKey = book.key ?: error("cannot bookmark an unkeyed book")
        val existing = Bookmarks.existingAt(
            db.bookmarkDao().forBook(bookKey.toString()).map { it.toBookmark() },
            chapterOrdinal,
            charOffset
        )
        if (existing != null) {
            if (label != null) db.bookmarkDao().setLabel(existing.key.toString(), label.trim().takeIf { it.isNotBlank() })
            return existing
        }

        val bookmark = Bookmarks.at(
            key = keys.next(EntityType.BOOKMARK),
            bookKey = bookKey,
            book = book,
            chapterOrdinal = chapterOrdinal,
            charOffset = charOffset,
            label = label,
            now = now
        )
        db.bookmarkDao().upsert(
            BookmarkEntity(
                key = bookmark.key.toString(),
                bookKey = bookKey.toString(),
                chapterOrdinal = bookmark.chapterOrdinal,
                charOffset = bookmark.charOffset,
                snippet = bookmark.snippet,
                chapterTitle = bookmark.chapterTitle,
                label = bookmark.label,
                createdAt = bookmark.createdAt
            )
        )
        checkpointKey(EntityType.BOOKMARK)
        return bookmark
    }

    suspend fun deleteBookmark(key: String) = db.bookmarkDao().delete(key)

    suspend fun setBookmarkLabel(key: String, label: String?) =
        db.bookmarkDao().setLabel(key, label?.trim()?.takeIf { it.isNotBlank() })

    // --- Reading pace ----------------------------------------------------------------------------
    //
    // Observed, never assumed. The reading meter already refuses to count time you were not
    // reading, so characters-per-minute can simply be measured — which is what lets a time estimate
    // be shown at all without inventing a words-per-minute for the user.

    /**
     * Record a stretch of reading against a book and against the reader overall.
     *
     * Both are kept because neither alone is right: a book with little history of its own is best
     * estimated from how this person reads generally, and a book with plenty is best estimated from
     * itself — a dense technical book and a novel are not read at the same speed. Implausible
     * samples are dropped inside [ReadingPace], so a jump-to-chapter cannot poison an estimate real
     * reading built.
     */
    suspend fun recordPace(
        bookKey: String,
        characters: Int,
        engagedMillis: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (characters <= 0 || engagedMillis <= 0) return
        listOf(bookKey, ReadingPaceEntity.GLOBAL).forEach { scope ->
            val stored = db.readingPaceDao().get(scope)
            val updated = ReadingPace(stored?.characters ?: 0, stored?.millis ?: 0)
                .observe(characters, engagedMillis)
            db.readingPaceDao().upsert(
                ReadingPaceEntity(
                    bookKey = scope,
                    characters = updated.characters,
                    millis = updated.millis,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * The pace to estimate this book with: its own once it is confident, otherwise the reader's
     * overall pace, otherwise nothing.
     */
    suspend fun paceFor(bookKey: String): ReadingPace {
        val own = db.readingPaceDao().get(bookKey)?.let { ReadingPace(it.characters, it.millis) }
        if (own != null && own.confident) return own
        val global = db.readingPaceDao().get(ReadingPaceEntity.GLOBAL)
        return global?.let { ReadingPace(it.characters, it.millis) } ?: ReadingPace()
    }

    // --- OPDS catalogs ------------------------------------------------------------------------
    //
    // The acquisition half of a library. One protocol reaches a self-hosted Calibre server,
    // Standard Ebooks, Gutenberg, Feedbooks, Kavita/Komga and most lending platforms, so browsing
    // is a first-class native surface rather than a WebView pointed at somebody's site: the entries
    // are data, which is what lets a download land in the library with its metadata attached.

    /** Saved catalogs, in order, with whether a sign-in is on file for each. */
    val catalogs: Flow<List<CatalogSource>> =
        db.opdsCatalogDao().observeAll().map { list -> list.map { it.toSource() } }

    private fun OpdsCatalogEntity.toSource(): CatalogSource {
        val creds = catalogCredentials?.credentials(id)
        return CatalogSource(
            id = id,
            name = name,
            url = url,
            username = creds?.username,
            password = creds?.password,
            position = position
        )
    }

    /**
     * Seed the catalog list the first time it is opened.
     *
     * The presets are all free and public, and exist so the screen is useful before the user has
     * typed a server address — an empty "add a catalog" form is a worse first impression than three
     * libraries you can browse immediately. Seeding happens once; a user who deletes them all gets
     * an empty list, not the presets back.
     */
    suspend fun seedCatalogsIfEmpty(now: Long = System.currentTimeMillis()) {
        if (db.opdsCatalogDao().count() > 0) return
        CatalogSource.presets().forEach { preset ->
            db.opdsCatalogDao().upsert(
                OpdsCatalogEntity(
                    id = preset.id,
                    name = preset.name,
                    url = preset.url,
                    position = preset.position,
                    createdAt = now
                )
            )
        }
    }

    suspend fun addCatalog(
        name: String,
        url: String,
        username: String? = null,
        password: String? = null,
        now: Long = System.currentTimeMillis()
    ): CatalogSource {
        val id = "OPDS-" + now.toString(36)
        val existing = db.opdsCatalogDao().all()
        val entity = OpdsCatalogEntity(
            id = id,
            name = name.trim().ifBlank { CatalogSource.normalizeRoot(url).substringAfter("://").substringBefore('/') },
            url = CatalogSource.normalizeRoot(url),
            position = existing.size,
            createdAt = now
        )
        db.opdsCatalogDao().upsert(entity)
        if (!username.isNullOrBlank()) {
            catalogCredentials?.setCredentials(id, username, password.orEmpty())
        }
        return entity.toSource()
    }

    /** Remove a catalog and forget its sign-in with it — the secret outliving the row would be a leak. */
    suspend fun deleteCatalog(id: String) {
        db.opdsCatalogDao().delete(id)
        catalogCredentials?.clear(id)
    }

    suspend fun setCatalogCredentials(id: String, username: String, password: String) {
        catalogCredentials?.setCredentials(id, username, password)
    }

    suspend fun catalog(id: String): CatalogSource? = db.opdsCatalogDao().get(id)?.toSource()

    /** Open a catalog page: its root when [url] is null, otherwise the page a link pointed at. */
    suspend fun openCatalog(
        source: CatalogSource,
        url: String? = null,
        now: Long = System.currentTimeMillis()
    ): OpdsClient.Result<CatalogPage> {
        val target = url ?: source.rootUrl
        val result = opdsClient.feed(source, target)
        if (result is OpdsClient.Result.Success) db.opdsCatalogDao().touchOpened(source.id, now)
        return result.map { CatalogPage(source, target, it) }
    }

    /** Search a catalog from the page currently open (which is what carries the search link). */
    suspend fun searchCatalog(page: CatalogPage, terms: String): OpdsClient.Result<CatalogPage> =
        opdsClient.search(page.source, page.feed, terms)
            .map { CatalogPage(page.source, page.url, it, query = terms) }

    /** Fetch a cover thumbnail for the catalog browser. Null on any failure — a cover is decoration. */
    suspend fun catalogImage(source: CatalogSource, url: String): ByteArray? =
        runCatching { opdsClient.image(source, url) }.getOrNull()

    /** What happened when the user tapped Download on a catalog entry. */
    sealed class AcquireResult {
        /** In the library, and openable. */
        data class Added(val bookKey: String, val title: String) : AcquireResult()

        /** Already held — the catalog entry matched a book on the shelf, so nothing was fetched. */
        data class AlreadyHave(val bookKey: String, val title: String) : AcquireResult()

        /** Downloaded, but Citation has no reader for it. */
        data class UnsupportedFormat(val label: String) : AcquireResult()

        data class Failed(val reason: String) : AcquireResult()
    }

    /**
     * Download a catalog entry into the library.
     *
     * The catalog's own metadata is merged over the file's afterwards, because a catalog usually
     * knows more than the file does — a Calibre server has series, tags and a blurb that the EPUB's
     * OPF often omits entirely, and losing that on the way in would make a downloaded book poorer
     * than the row you tapped.
     *
     * Format is decided by what was actually served, not by what the link claimed: servers
     * mislabel, and the bytes do not.
     */
    suspend fun acquire(
        source: CatalogSource,
        entry: OpdsEntry,
        now: Long = System.currentTimeMillis()
    ): AcquireResult {
        // An ISBN the catalog states is strong enough evidence to skip a download entirely.
        entry.isbn?.let { isbn ->
            db.bookDao().findBySource(isbn, SourceType.EPUB.name)?.let {
                return AcquireResult.AlreadyHave(it.key, it.title)
            }
        }

        val link = entry.preferredDownload ?: return AcquireResult.Failed("Nothing to download")

        val download = when (val result = opdsClient.download(source, link.href)) {
            is OpdsClient.Result.Success -> result.value
            is OpdsClient.Result.Unauthorized -> return AcquireResult.Failed("Sign-in required")
            is OpdsClient.Result.HttpError -> return AcquireResult.Failed("Server said ${result.code}")
            is OpdsClient.Result.Unreachable -> return AcquireResult.Failed(result.message)
            is OpdsClient.Result.NotACatalog -> return AcquireResult.Failed("Unexpected response")
            is OpdsClient.Result.Unsupported -> return AcquireResult.Failed("Not offered")
        }

        // Sniff the magic number rather than trusting the served type — the same rule MainActivity
        // uses for a file handed in from outside, and for the same reason.
        val bytes = download.bytes
        return when {
            bytes.looksLikeEpub() -> {
                val book = importEpub(bytes, now) ?: return AcquireResult.Failed("Could not read the EPUB")
                val key = book.key?.toString() ?: return AcquireResult.Failed("Could not read the EPUB")
                mergeCatalogMetadata(key, entry, source)
                AcquireResult.Added(key, book.metadata.title)
            }
            bytes.looksLikePdf() -> {
                val key = importPdf(bytes, entry.title, now)
                mergeCatalogMetadata(key, entry, source)
                AcquireResult.Added(key, entry.title)
            }
            else -> AcquireResult.UnsupportedFormat(link.formatLabel)
        }
    }

    /**
     * Fill in what the catalog knew and the file did not.
     *
     * Deliberately additive: a value the file supplied wins, because it came from the publisher's
     * own package document, and only the gaps are filled from the catalog row. The exception is the
     * cover — if the book has none and the catalog offers one, fetching it is worth a round trip,
     * since a shelf of blank rectangles is the thing a library screen most needs to avoid.
     */
    private suspend fun mergeCatalogMetadata(bookKey: String, entry: OpdsEntry, source: CatalogSource) {
        val stored = db.bookDao().get(bookKey) ?: return
        val merged = stored.copy(
            author = stored.author ?: entry.author,
            publisher = stored.publisher ?: entry.publisher,
            published = stored.published ?: entry.published,
            description = stored.description ?: entry.summary,
            series = stored.series ?: entry.series,
            seriesIndex = stored.seriesIndex ?: entry.seriesIndex,
            subjectsJson = CitationMappers.mergeSubjects(stored.subjectsJson, entry.categories),
            sourceId = stored.sourceId ?: entry.isbn
        )
        if (merged != stored) db.bookDao().upsert(merged)

        if (stored.coverPath == null) {
            entry.cover?.let { url ->
                runCatching {
                    opdsClient.image(source, url)?.let { bytes ->
                        db.bookDao().setCoverPath(bookKey, files.writeCover(bookKey, bytes).absolutePath)
                    }
                }
            }
        }
    }

    private fun ByteArray.looksLikeEpub(): Boolean =
        size > 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private fun ByteArray.looksLikePdf(): Boolean =
        size > 4 && this[0] == 0x25.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x44.toByte() && this[3] == 0x46.toByte()

    /**
     * Re-read a book from the file it was imported from, to pick up what a newer parser can see.
     *
     * Without this, structure, covers, illustrations and shelf metadata would only ever apply to
     * books added *after* the parser learned to find them — a library built up over a year would
     * stay plain forever, for no reason other than when it happened to be imported. The source file
     * is kept precisely so this is possible.
     *
     * The safety condition is exact and enforced per chapter: structure is written **only where the
     * re-parsed text is byte-identical to the text already stored**. Where it is, the offsets every
     * note anchored against are provably unchanged, so adding structure cannot move an anchor;
     * where it somehow is not, that chapter is left exactly as it was. Titles, reading position,
     * lifecycle and notes are never touched — only derived data is refreshed.
     */
    suspend fun refreshFromFile(bookKey: String, now: Long = System.currentTimeMillis()): RefreshResult {
        val entity = db.bookDao().get(bookKey) ?: return RefreshResult.NotRefreshable
        if (entity.sourceType != SourceType.EPUB.name && entity.sourceType != SourceType.AO3.name) {
            return RefreshResult.NotRefreshable
        }
        val file = java.io.File(files.sovereignDir, "$bookKey.epub")
        if (!file.exists()) return RefreshResult.FileMissing

        val parsed = runCatching { EpubParser.parse(file.readBytes()) }.getOrNull()
            ?: return RefreshResult.Unreadable

        val stored = db.chapterDao().forBook(bookKey).associateBy { it.ordinal }
        var updated = 0
        var skipped = 0
        parsed.book.chapters.forEach { chapter ->
            val existing = stored[chapter.ordinal] ?: return@forEach
            if (existing.text != chapter.text) {
                skipped++
                return@forEach
            }
            db.chapterDao().upsert(
                existing.copy(
                    blocksJson = chapter.blocks.takeIf { it.isNotEmpty() }?.let { BlockCodec.encodeBlocks(it) },
                    anchorsJson = chapter.anchors.takeIf { it.isNotEmpty() }?.let { BlockCodec.encodeAnchors(it) }
                )
            )
            updated++
        }

        val coverPath = storeBookImages(bookKey, parsed) ?: entity.coverPath
        val meta = parsed.book.metadata
        db.bookDao().upsert(
            entity.copy(
                // Derived fields only. The title the user sees, the author, the source binding and
                // both state machines stay as they are — a refresh is not a re-import.
                publisher = entity.publisher ?: meta.publisher,
                published = entity.published ?: meta.published,
                description = entity.description ?: meta.description,
                subjectsJson = CitationMappers.mergeSubjects(entity.subjectsJson, meta.subjects),
                series = entity.series ?: meta.series,
                seriesIndex = entity.seriesIndex ?: meta.seriesIndex,
                coverPath = coverPath,
                chapterCount = if (entity.chapterCount > 0) entity.chapterCount else parsed.book.chapters.size,
                tocJson = entity.tocJson ?: parsed.book.toc.takeIf { !it.isEmpty }?.let { BlockCodec.encodeToc(it) }
            )
        )
        return RefreshResult.Refreshed(chapters = updated, unchanged = skipped)
    }

    /** What a re-read of a book's source file achieved. */
    sealed class RefreshResult {
        data class Refreshed(val chapters: Int, val unchanged: Int) : RefreshResult()

        /** This source has no file to re-read — a serial, a read-in-place licence, a PDF. */
        object NotRefreshable : RefreshResult()

        object FileMissing : RefreshResult()
        object Unreadable : RefreshResult()
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
    private fun storeBookImages(bookKey: String, parsed: EpubParser.ParsedEpub): String? {
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

    // --- Collections ------------------------------------------------------------------------
    //
    // Shelves the user makes by hand. Deliberately not rule-based: a smart collection needs a query
    // language and an explanation for why a book vanished from it; a shelf you put books on needs
    // neither, and series/subject grouping already covers the automatic view.

    suspend fun createCollection(name: String, now: Long = System.currentTimeMillis()): BookCollection {
        val trimmed = name.trim().ifBlank { "Untitled shelf" }
        val existing = db.collectionDao().all()
        val collection = CollectionEntity(
            id = "COL-" + now.toString(36) + "-" + (existing.size + 1),
            name = trimmed,
            position = existing.size,
            createdAt = now
        )
        db.collectionDao().upsert(collection)
        return BookCollection(collection.id, collection.name, collection.position)
    }

    suspend fun renameCollection(id: String, name: String) {
        name.trim().takeIf { it.isNotBlank() }?.let { db.collectionDao().rename(id, it) }
    }

    /** Remove a shelf. The books on it are untouched — a shelf is a view, not a container. */
    suspend fun deleteCollection(id: String) = db.collectionDao().delete(id)

    suspend fun addToCollection(collectionId: String, bookKey: String, now: Long = System.currentTimeMillis()) {
        db.collectionDao().addMember(CollectionMemberEntity(collectionId, bookKey, now))
    }

    suspend fun removeFromCollection(collectionId: String, bookKey: String) {
        db.collectionDao().removeMember(collectionId, bookKey)
    }

    /** Which shelves a book is on, for the book detail sheet's checklist. */
    suspend fun collectionsOf(bookKey: String): Set<String> =
        db.collectionDao().membershipsOf(bookKey).map { it.collectionId }.toSet()

    suspend fun setFavorite(bookKey: String, favorite: Boolean) =
        db.bookDao().setFavorite(bookKey, favorite)

    /** Mark a book finished (or put it back on the pile) from the library, without opening it. */
    suspend fun setReadingState(bookKey: String, state: ReadingState) =
        db.bookDao().setReadingState(bookKey, state.name)

    /** The cover file for a book, or null — the library screen decodes it directly. */
    fun coverFile(bookKey: String): java.io.File? = files.coverFile(bookKey)

    /** The stored image behind a chapter's illustration reference, or null if it was not kept. */
    fun bookAsset(bookKey: String, src: String): java.io.File? = files.readBookAsset(bookKey, src)

    /**
     * What the library and Read tabs show for one book, without loading a chapter. Widened from
     * "a title and two state words" to the shelf metadata a real library screen needs — a cover, a
     * series, subjects, and enough to compute progress.
     */
    data class BookSummary(
        val key: String,
        val title: String,
        val author: String?,
        val readingState: String,
        val acquisitionState: String,
        val sourceType: String,
        val lastChapterOrdinal: Int,
        val lastOpenedAt: Long?,
        val series: String? = null,
        val seriesIndex: Float? = null,
        val subjects: List<String> = emptyList(),
        val publisher: String? = null,
        val published: String? = null,
        val description: String? = null,
        /** Absolute path of the extracted cover, or null when the book has none. */
        val coverPath: String? = null,
        val chapterCount: Int = 0,
        val isFavorite: Boolean = false,
        val addedAt: Long = 0,
        /** What the reader last measured in characters, or 0 when it never has. */
        val progressFraction: Float = 0f
    ) {
        /** "The Expanse #1", or null. */
        val seriesLabel: String?
            get() = series?.let { name ->
                val index = seriesIndex ?: return@let name
                val trimmed = if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()
                "$name #$trimmed"
            }

        /**
         * How far through: what the reader measured in characters when it has, otherwise a coarse
         * chapter estimate. 0 for a book never opened, 1 for one marked done.
         */
        val progress: Float
            get() = when {
                readingState == "DONE" -> 1f
                lastOpenedAt == null -> 0f
                progressFraction > 0f -> progressFraction.coerceIn(0f, 1f)
                chapterCount <= 0 -> 0f
                else -> ((lastChapterOrdinal + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
            }
    }

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
