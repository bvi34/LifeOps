package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.audio.Narrator
import com.citation.app.data.AcquireResult
import com.citation.app.data.BookSummary
import com.citation.app.data.CitationRepository
import com.citation.app.data.KindleLibrary
import com.citation.app.data.KindleSession
import com.citation.app.data.OreillyAccess
import com.citation.app.data.OreillyCatalog
import com.citation.app.data.OreillySession
import com.citation.app.data.PdfSession
import com.citation.app.data.ReflowResult
import com.citation.app.data.RefreshResult
import com.citation.app.data.acquire
import com.citation.app.data.addBookmark
import com.citation.app.data.addCatalog
import com.citation.app.data.addKindleBook
import com.citation.app.data.addOreillyBook
import com.citation.app.data.addToCollection
import com.citation.app.data.beginReaderContext
import com.citation.app.data.bookAsset
import com.citation.app.data.bookmarks
import com.citation.app.data.captureExternalNote
import com.citation.app.data.captureNote
import com.citation.app.data.capturePdfNote
import com.citation.app.data.captureSynthesis
import com.citation.app.data.catalogImage
import com.citation.app.data.clearOreillyCredentials
import com.citation.app.data.clearReaderSettings
import com.citation.app.data.coverFile
import com.citation.app.data.createCollection
import com.citation.app.data.deleteBookmark
import com.citation.app.data.deleteCatalog
import com.citation.app.data.deleteCollection
import com.citation.app.data.deleteReaderFont
import com.citation.app.data.editNoteBody
import com.citation.app.data.endReaderContext
import com.citation.app.data.hasOwnReaderSettings
import com.citation.app.data.hasPdfFlow
import com.citation.app.data.importKindleNotebook
import com.citation.app.data.importPdf
import com.citation.app.data.kindleLibrary
import com.citation.app.data.kindleSession
import com.citation.app.data.linkNoteToBook
import com.citation.app.data.opds.OpdsClient
import com.citation.app.data.openCatalog
import com.citation.app.data.oreillyAccessConfig
import com.citation.app.data.oreillyCatalog
import com.citation.app.data.oreillySession
import com.citation.app.data.oreillyWarmCacheStale
import com.citation.app.data.paceFor
import com.citation.app.data.pdfSession
import com.citation.app.data.readerFonts
import com.citation.app.data.readerSettingsFor
import com.citation.app.data.recordPace
import com.citation.app.data.recordReadingTelemetry
import com.citation.app.data.reflowPdf
import com.citation.app.data.refreshFromFile
import com.citation.app.data.removeFromCollection
import com.citation.app.data.renameCollection
import com.citation.app.data.renameReaderFont
import com.citation.app.data.resolveNote
import com.citation.app.data.saveExternalPosition
import com.citation.app.data.saveReaderSettings
import com.citation.app.data.searchCatalog
import com.citation.app.data.seedCatalogsIfEmpty
import com.citation.app.data.setBookmarkLabel
import com.citation.app.data.setCatalogCredentials
import com.citation.app.data.setFavorite
import com.citation.app.data.setNoteHighlight
import com.citation.app.data.setNoteTags
import com.citation.app.data.setOreillyCredentials
import com.citation.app.data.setOreillyProxyHost
import com.citation.app.data.setReadingState
import com.citation.app.data.storageReport
import com.citation.app.data.storeReaderFont
import com.citation.app.data.sync
import com.citation.core.capture.CaptureClusterer
import com.citation.core.capture.CaptureTriage
import com.citation.core.library.BookCollection
import com.citation.core.library.Facet
import com.citation.core.library.LibraryEntry
import com.citation.core.library.LibraryFilter
import com.citation.core.library.LibraryQuery
import com.citation.core.library.LibrarySort
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.model.TocEntry
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.NoteSearch
import com.citation.core.note.NoteType
import com.citation.core.note.TagCount
import com.citation.core.note.Tags
import com.citation.core.opds.CatalogPage
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import com.citation.core.opds.OpdsFeed
import com.citation.core.pdf.PdfFlow
import com.citation.core.reader.BookSearch
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Bookmarks
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.ReadingMeter
import com.citation.core.reader.ReadingPace
import com.citation.core.reader.ReadingProgress
import com.citation.core.reader.TimeLeft
import com.citation.core.reader.VolumeKeys
import com.citation.core.speech.CustomVoice
import com.citation.core.speech.InstalledVoice
import com.citation.core.speech.NarrationState
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.Resume
import com.citation.core.speech.SavedPlace
import com.citation.core.speech.SkipGranularity
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.VoiceCatalog
import com.citation.core.speech.VoiceDraft
import com.citation.core.speech.VoiceModel
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the library + reader. Kept thin: it holds UI state as [StateFlow]s and delegates all
 * policy to [CitationRepository] (mirroring LifeOps' Screen → ViewModel → Repository shape). The
 * reader consumes the format-blind [Book] model, so nothing here knows or cares that the source was
 * an EPUB.
 *
 * **What is in this file, and what is not.** This file is the reader's *state*: every [StateFlow]
 * the screens observe, in the order a reading session builds it up — what is open, where you are,
 * what the shelf is showing, what is being said aloud. The behaviour that changes that state lives
 * beside it, one file per concern, as extensions on this class: `ReaderPosition`, `ReaderSettings`,
 * `ReaderSearch`, `ReaderBookmarks`, `ReaderShelf`, `ReaderCatalog`, `ReaderNotesQuery`,
 * `ReaderOpening`, `ReaderPdf`, `ReaderOreilly`, `ReaderKindle`, `ReaderSerials`,
 * `ReaderNavigation`, `ReaderNotes` and `ReaderNarration`. They are in this package, so the screens
 * call them exactly as they always did.
 *
 * Split that way round — state together, behaviour apart — because half of this state is not any one
 * concern's. The open book, the position in it, the pending-scroll intents and the status line are
 * one reading session that every concern reads and writes: opening a PDF, following a serial and
 * jumping to a note all move the same three flows. Giving each concern its own object would have
 * meant either splitting that session, which would let two of them disagree about where you are, or
 * passing it into all of them, which is this file with extra steps.
 */
class ReaderViewModel(
    internal val repository: CitationRepository,
    /**
     * The read-aloud narrator, when the host supplied one.
     *
     * Nullable because listening needs a `Context` and the ViewModel deliberately has none: the
     * activity builds the narrator and hands it over. A null one leaves every control below inert
     * and the reader exactly as it was before speech existed, which is also what makes the rest of
     * this class testable without an Android runtime.
     */
    internal val narrator: Narrator? = null
) : ViewModel() {

    val books: StateFlow<List<BookSummary>> =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every captured note, newest first, for the Notes screen. */
    val notes: StateFlow<List<Note>> =
        repository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The most recently opened book, driving the Read tab's resume card. */
    val lastOpened: StateFlow<BookSummary?> =
        repository.lastOpened.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The thin-context triage queue: cross-app captures whose best identifier is only an app name or
     * a timestamp, still unbound. Derived reactively from the notes stream, so it clears itself as
     * captures get promoted or tagged. Drives the Personal tab's triage prompt.
     */
    val triage: StateFlow<List<CaptureClusterer.ProvisionalSource>> =
        repository.notes.map { CaptureTriage.queue(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Where you are, and how much is left ------------------------------------------------------
    //
    // "Chapter 3 / 40" is a location, not progress. Everything here is derived from the character
    // position, and the time estimate comes from the reader's *own* measured pace — shown only once
    // there is enough honest reading behind it to mean something.

    // The open book and the chapter within it. They are declared here, above every flow that
    // combines them, because a property initializer that reads a property declared further down the
    // class reads it before it is assigned — Kotlin rejects it outright, and at runtime it would be
    // null. Their public `asStateFlow()` faces stay with the reader state they belong to, below.
    internal val _openBook = MutableStateFlow<Book?>(null)
    internal val _chapterOrdinal = MutableStateFlow(0)

    internal val _position = MutableStateFlow(0 to 0)

    /** The pace to estimate the open book with; reloaded whenever a book is opened. */
    internal val _pace = MutableStateFlow(ReadingPace())

    /** Where you are in the open book, in characters. Null when nothing is open. */
    val progress: StateFlow<ReadingProgress.Position?> =
        combine(_openBook, _position) { book, position ->
            book?.let { ReadingProgress.at(it, position.first, position.second) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "4 min left", or null when there is not yet enough evidence to say. */
    val timeLeft: StateFlow<String?> =
        combine(progress, _pace) { position, pace ->
            position?.let { TimeLeft.label(pace.millisFor(it.charactersLeft)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "6 min left in chapter", same rules. */
    val chapterTimeLeft: StateFlow<String?> =
        combine(_openBook, _position, _pace) { book, position, pace ->
            book ?: return@combine null
            val left = ReadingProgress.charactersLeftInChapter(book, position.first, position.second)
            TimeLeft.inChapter(pace.millisFor(left))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Characters covered since the last pace report, paired with the meter's engaged time. */
    internal var paceCharacters = 0

    // --- Reader settings --------------------------------------------------------------------------
    //
    // Display settings used to live in composition state, so a reader's text size, margins and theme
    // were lost on every app restart. They are persisted now, and a book can be told to keep its own
    // set — which is a *complete* fork rather than a patch, so changing the global font later cannot
    // silently change a book you had already set up the way you wanted.

    /** The settings the open book is actually being read with. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settings: StateFlow<ReaderSettings> =
        _openBook.flatMapLatest { book ->
            val key = book?.key?.toString()
            if (key == null) repository.globalReaderSettings else repository.readerSettingsFor(key)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    internal val _perBookSettings = MutableStateFlow(false)

    /** Whether the open book keeps settings of its own rather than following the global ones. */
    val perBookSettings: StateFlow<Boolean> = _perBookSettings.asStateFlow()

    internal val _readerFonts = MutableStateFlow(repository.readerFonts())

    /** Fonts the reader has added, each under the name it goes by, for the picker to offer again. */
    val readerFonts: StateFlow<List<ReaderFont>> = _readerFonts.asStateFlow()

    /**
     * Whether the screen stays on while reading. Read off the settings so it covers every reader
     * track, not just the flowing one.
     */
    val keepAwake: StateFlow<Boolean> =
        settings.map { it.keepAwake }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    internal val _pageTurns = MutableStateFlow(0L)

    /**
     * A ticking counter of page turns asked for by something other than a gesture — a volume key.
     *
     * A counter rather than the action itself, because two presses of the *same* key must both be
     * seen: a flow carrying `NEXT_PAGE` twice in a row would not emit the second time, and the page
     * would silently refuse to turn.
     */
    val pageTurns: StateFlow<Long> = _pageTurns.asStateFlow()

    internal var pendingPageTurn: VolumeKeys.Action? = null

    // --- In-book search ---------------------------------------------------------------------------

    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    internal val _searchHits = MutableStateFlow<List<BookSearch.Hit>>(emptyList())
    val searchHits: StateFlow<List<BookSearch.Hit>> = _searchHits.asStateFlow()

    internal val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    /**
     * Hits in the chapter on screen, so the words you searched for are lit up when you land on
     * them. Canonical ranges — the reader converts them like any other highlight.
     */
    val searchRanges: StateFlow<List<IntRange>> =
        combine(_searchHits, _chapterOrdinal, _searchOpen) { hits, ordinal, open ->
            if (!open) emptyList() else hits.filter { it.chapterOrdinal == ordinal }.map { it.range }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Bookmarks --------------------------------------------------------------------------------

    /** Bookmarks in the open book, in reading order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<Bookmark>> =
        _openBook.flatMapLatest { book ->
            val key = book?.key?.toString()
            if (key == null) kotlinx.coroutines.flow.flowOf(emptyList()) else repository.bookmarks(key)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The bookmark covering where you are, if there is one — so one control can be a toggle. */
    val bookmarkHere: StateFlow<Bookmark?> =
        combine(bookmarks, _position) { marks, position ->
            Bookmarks.existingAt(marks, position.first, position.second)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- The library shelf ----------------------------------------------------------------------
    //
    // A flat, unsorted, unsearchable column of titles works for a dozen books and is useless for
    // hundreds — and connecting a catalog makes hundreds the normal case. All the arranging is
    // pure `:core` logic (LibraryQuery); this holds the live filter/sort the screen drives.

    /** Every book with its shelf metadata and the collections it sits on. */
    val library: StateFlow<List<LibraryEntry>> =
        repository.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val collections: StateFlow<List<BookCollection>> =
        repository.collections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    internal val _libraryFilter = MutableStateFlow(LibraryFilter())
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    internal val _librarySort = MutableStateFlow(LibrarySort.RECENT)
    val librarySort: StateFlow<LibrarySort> = _librarySort.asStateFlow()

    internal val _libraryGrid = MutableStateFlow(true)
    /** Grid of covers versus a denser list. A per-device preference, not a synced one. */
    val libraryGrid: StateFlow<Boolean> = _libraryGrid.asStateFlow()

    /** The books actually shown: the shelf, narrowed and ordered. Recomputed live as you type. */
    val shelf: StateFlow<List<LibraryEntry>> =
        combine(library, _libraryFilter, _librarySort) { all, filter, sort ->
            LibraryQuery.apply(all, filter, sort)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Subject facets over the *unfiltered* library, so the chip row stays put as you narrow rather
     * than collapsing to whatever is left and stranding you.
     */
    val libraryFacets: StateFlow<List<Facet>> =
        library.map { LibraryQuery.subjectCounts(it).take(24) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Catalog browsing -----------------------------------------------------------------------
    //
    // The acquisition half of the library. Browsing is a native surface rather than a WebView on
    // someone's site, because the entries are data: that is what lets a tap become a download that
    // lands in the library with its series, subjects and blurb already attached.

    val catalogs: StateFlow<List<CatalogSource>> =
        repository.catalogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    internal val _catalogPage = MutableStateFlow<CatalogPage?>(null)
    /** The catalog page on screen, or null when the browser is closed. */
    val catalogPage: StateFlow<CatalogPage?> = _catalogPage.asStateFlow()

    internal val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    internal val _catalogError = MutableStateFlow<String?>(null)
    /** Why the last catalog fetch failed, phrased for a person. Cleared on the next success. */
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** Previous pages, so Back walks up a catalog the way it walked down. */
    internal val catalogStack = ArrayDeque<CatalogPage>()

    val canGoBackInCatalog: Boolean get() = catalogStack.isNotEmpty()

    internal val _catalogsOpen = MutableStateFlow(false)
    /** Whether the catalogs surface has taken over the shell (it preempts, like the readers do). */
    val catalogsOpen: StateFlow<Boolean> = _catalogsOpen.asStateFlow()

    // Thumbnails are fetched once per browsing session and dropped when the browser closes. A
    // catalog page is a few dozen covers; caching them is what keeps scrolling back up from
    // re-downloading the shelf, and clearing on close is what keeps that from growing unbounded.
    // Concurrent because a screenful of covers all start loading at once; misses are remembered
    // separately so a cover the server doesn't have is asked for once, not on every recomposition.
    internal val catalogThumbnails = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    internal val catalogThumbnailMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal val _acquiring = MutableStateFlow<Set<String>>(emptySet())
    /** Entry ids currently downloading, so their rows can show it. */
    val acquiring: StateFlow<Set<String>> = _acquiring.asStateFlow()

    // --- Notes retrieval: free-text search + tag facet, and Markdown export ---------------------

    internal val _query = MutableStateFlow("")
    /** The live search text over notes (matches body, frozen snapshot, title/author, and tags). */
    val query: StateFlow<String> = _query.asStateFlow()

    internal val _activeTag = MutableStateFlow<String?>(null)
    /** The tag currently filtering the notes list, or null for "all tags". */
    val activeTag: StateFlow<String?> = _activeTag.asStateFlow()

    /** Every tag across all notes with its note-count, most-used first — the facet row's data. */
    val tagCounts: StateFlow<List<TagCount>> =
        notes.map { Tags.counts(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The notes actually shown: the full corpus narrowed by the active tag (if any), then the search
     * query. Recomputed reactively, so typing or picking a tag re-filters live — and it's exactly
     * what an export writes ("export what I'm looking at").
     */
    val filteredNotes: StateFlow<List<Note>> =
        combine(notes, _activeTag, _query) { all, tag, q ->
            val byTag = if (tag == null) all else Tags.withTag(all, tag)
            NoteSearch.match(byTag, q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openBook: StateFlow<Book?> = _openBook.asStateFlow()

    /**
     * The passage-anchored notes of the open book — the reader renders these as inline highlights, and
     * a tap on one opens it. Derived from the notes stream so a fresh capture appears under your finger
     * immediately, and clears when you close the book.
     */
    val openHighlights: StateFlow<List<Note>> =
        combine(_openBook, repository.notes) { book, notes ->
            val key = book?.key?.toString() ?: return@combine emptyList<Note>()
            notes.filter { it.type == NoteType.PASSAGE_ANCHORED && it.source.bookKey?.toString() == key }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chapterOrdinal: StateFlow<Int> = _chapterOrdinal.asStateFlow()

    // Scroll position to restore on the first paint of a reopened book: the chapter it belongs to and
    // the pixel offset within it. Consumed once by the reader, so a page turn doesn't re-apply it.
    internal var pendingScrollChapter = -1
    internal var pendingScrollOffset = 0

    // A pending restore whose offset is *canonical text*, not whatever the open reading mode stores.
    // It exists because the voice only ever knows characters: resuming where listening left off has
    // to be resolved against the rendered chapter (to a page, or to a line's y) rather than handed
    // to `scrollTo` as though it were pixels. Kept beside the older channel rather than replacing
    // it, so an ordinary reading resume behaves exactly as it did.
    internal var pendingCanonicalChapter = -1
    internal var pendingCanonicalOffset = 0

    // The chapter a *backward* page turn is entering, which must open at its end rather than its
    // start. Only the reader knows where that chapter's last page begins — it depends on the live
    // typography and viewport — so the intent is recorded here and the reader resolves it, exactly
    // like pendingScroll above. Consumed once, so a later turn doesn't get pulled back to the end.
    internal var pendingEndChapter = -1

    internal val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // Set only when an import arriving from *outside* the app fails; drawn as an alert over the
    // reader (see reportImportProblem), because such an import has no picker screen to report back to.
    internal val _importAlert = MutableStateFlow<String?>(null)
    val importAlert: StateFlow<String?> = _importAlert.asStateFlow()

    // --- Engaged-reading meter -----------------------------------------------------------------
    // Measures only *active* reading time: it accrues between progress signals (page turns, scrolls,
    // chapter advances), each interval capped, and pauses when the reader isn't visible. So leaving
    // the app open on a page can't inflate the count — which is what lets reading be rewarded without
    // an output cap. Drained to whole minutes and posted up the mailbox as telemetry.
    internal val readingMeter = ReadingMeter()
    internal var readingBookKey: String? = null
    // Sub-minute engaged time carried across a pause/resume so brief backgrounding doesn't lose it.
    internal var readingRemainderMillis = 0L

    // Non-null while a Royal Road serial is open, so page turns can slide its prefetch buffer.
    internal var openRrFictionId: Long? = null

    // The PDF paged reader and the O'Reilly read-in-place reader are separate tracks from the
    // flowing reader; when one is set the UI shows that track instead.
    internal val _pdfSession = MutableStateFlow<PdfSession?>(null)
    val pdfSession: StateFlow<PdfSession?> = _pdfSession.asStateFlow()

    internal val _oreillySession = MutableStateFlow<OreillySession?>(null)
    val oreillySession: StateFlow<OreillySession?> = _oreillySession.asStateFlow()

    internal val _kindleSession = MutableStateFlow<KindleSession?>(null)
    val kindleSession: StateFlow<KindleSession?> = _kindleSession.asStateFlow()

    // Non-null while browsing the O'Reilly catalog (proxied through your library); the catalog surface
    // preempts the home shell, mirroring Browse Royal Road.
    internal val _oreillyCatalog = MutableStateFlow<OreillyCatalog?>(null)
    val oreillyCatalog: StateFlow<OreillyCatalog?> = _oreillyCatalog.asStateFlow()

    // Non-null while browsing your Kindle library on read.amazon.com; the browse surface preempts the
    // home shell, exactly like the O'Reilly catalog — you skim the shelf and tap a book to open it.
    internal val _kindleLibrary = MutableStateFlow<KindleLibrary?>(null)
    val kindleLibrary: StateFlow<KindleLibrary?> = _kindleLibrary.asStateFlow()

    // --- PDF: the two tracks over one file ------------------------------------------------------
    // A PDF is a picture of glyphs, so the paged render can't be selected from. Reflowing extracts its
    // text into the same format-blind Book the flowing reader already draws, which is what makes a PDF
    // quotable at all. The reflow is derived: the file and its pages remain the ground truth, and the
    // reader switches between them without losing your place.

    internal val _pdfFlowReady = MutableStateFlow(false)
    /** Whether the open PDF has a reflowed text track available (drives the reader's track toggle). */
    val pdfFlowReady: StateFlow<Boolean> = _pdfFlowReady.asStateFlow()

    internal val _pdfInitialPage = MutableStateFlow(0)
    /** The page the paged reader opens on — carried over when arriving from the text track. */
    val pdfInitialPage: StateFlow<Int> = _pdfInitialPage.asStateFlow()

    internal val _reflowing = MutableStateFlow(false)
    /** True while a PDF's text is being extracted — the toggle shows progress rather than nothing. */
    val reflowing: StateFlow<Boolean> = _reflowing.asStateFlow()

    // --- O'Reilly library access (proxy host + encrypted card/PIN) ------------------------------

    internal val _oreillyConfig = MutableStateFlow(
        OreillyAccess.Config(com.citation.core.oreilly.OreillyLibraryProxy.MID_CONTINENT_HOST, false)
    )
    /** Drives the Settings section: the library proxy host and whether a card/PIN are on file. */
    val oreillyConfig: StateFlow<OreillyAccess.Config> = _oreillyConfig.asStateFlow()

    init {
        // Load the stored access config off the constructor's path (Keystore-backed read).
        viewModelScope.launch { _oreillyConfig.value = repository.oreillyAccessConfig() }
    }

    // --- Kindle read-in-place (read.amazon.com) -------------------------------------------------

    val isRoyalRoadOpen: Boolean get() = openRrFictionId != null

    // --- Reading aloud ----------------------------------------------------------------------------
    //
    // The narrator is the same book, in the same position, spoken instead of set. Everything here is
    // a thin pass-through: what to say and where the voice is live in `:core` and in the narrator,
    // and duplicating any of it here is how the page and the voice would come to disagree about
    // where the reader is.

    init {
        // Claim the page-following hook now rather than at the first play: the narrator is a
        // process-wide singleton that may already be reading when this ViewModel is built (an
        // activity recreated while a backgrounded book carried on), and the reference it holds then
        // belongs to a ViewModel that no longer exists.
        narrator?.onPosition = ::onNarratedPosition
    }

    internal val idleNarration = MutableStateFlow(NarrationState())

    /** What the voice is doing, and which sentence it is on — the read-along highlight's source. */
    val narration: StateFlow<NarrationState> = narrator?.state ?: idleNarration.asStateFlow()

    /** Voice, speed and what gets spoken. */
    val speechSettings: StateFlow<SpeechSettings> =
        narrator?.speechSettings ?: MutableStateFlow(SpeechSettings()).asStateFlow()

    /** Whether this build can speak at all — false only when no narrator was supplied. */
    val canReadAloud: Boolean get() = narrator != null

    /** Whether a downloadable neural voice can be run here, as opposed to the platform's own. */
    val neuralVoicesSupported: Boolean get() = narrator?.neuralAvailable == true

    internal val _installedVoices = MutableStateFlow(narrator?.installedVoices().orEmpty())

    /**
     * The voices already downloaded, for the picker.
     *
     * A flow rather than a call, because the set changes underneath the screen showing it — a
     * download finishing, a deletion — and a list that only refreshed when something else happened
     * to recompose is how a deleted voice stays on screen.
     */
    val installedVoices: StateFlow<List<InstalledVoice>> = _installedVoices.asStateFlow()

    internal val _userVoices = MutableStateFlow(narrator?.userVoices().orEmpty())

    /**
     * The voices the reader added themselves, whether or not their files arrived.
     *
     * Separate from [installedVoices] because it answers a different question. That one is "what can
     * speak"; this one is "what did I add" — and the two differ exactly when a download failed,
     * which is the moment the reader most needs to see the voice still listed, with a retry on it,
     * rather than to be shown an empty picker and left to type the link again.
     */
    val userVoices: StateFlow<List<VoiceModel>> = _userVoices.asStateFlow()

    /** The book the voice has open — which need not be the book on screen. */
    val nowPlayingBook: String? get() = narrator?.bookTitle

    /** Its author, for the same card. */
    val nowPlayingAuthor: String? get() = narrator?.bookAuthor

    /** Why the voice stopped, when it stopped badly. */
    val speechFailure: String? get() = narrator?.lastFailure

    internal val _voiceProgress = MutableStateFlow<Pair<String, Float>?>(null)

    /** The voice being downloaded and how far along it is, or null when none is. */
    val voiceProgress: StateFlow<Pair<String, Float>?> = _voiceProgress.asStateFlow()

}
