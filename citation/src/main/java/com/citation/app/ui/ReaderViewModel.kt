package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.data.CitationRepository
import com.citation.app.data.opds.OpdsClient
import com.citation.app.data.OreillyAccess
import com.citation.app.audio.Narrator
import com.citation.core.speech.InstalledVoice
import com.citation.core.speech.NarrationState
import com.citation.core.speech.Resume
import com.citation.core.speech.SavedPlace
import com.citation.core.speech.NarrationStatus
import com.citation.core.speech.SkipGranularity
import com.citation.core.speech.SleepMode
import com.citation.core.speech.SpeechSettings
import com.citation.core.speech.VoiceCatalog
import com.citation.core.speech.VoiceModel
import com.citation.core.capture.CaptureClusterer
import com.citation.core.capture.CaptureTriage
import com.citation.core.library.BookCollection
import com.citation.core.library.Facet
import com.citation.core.library.LibraryEntry
import com.citation.core.library.LibraryFilter
import com.citation.core.library.LibraryQuery
import com.citation.core.library.LibrarySort
import com.citation.core.opds.CatalogPage
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import com.citation.core.opds.OpdsFeed
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.model.TocEntry
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.NoteSearch
import com.citation.core.pdf.PdfFlow
import com.citation.core.reader.ReadingMeter
import com.citation.core.reader.BookSearch
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Bookmarks
import com.citation.core.reader.ReadingPace
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.ReadingProgress
import com.citation.core.reader.TimeLeft
import com.citation.core.reader.VolumeKeys
import com.citation.core.note.NoteType
import com.citation.core.note.TagCount
import com.citation.core.note.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives the library + reader. Kept thin: it holds UI state as [StateFlow]s and delegates all
 * policy to [CitationRepository] (mirroring LifeOps' Screen → ViewModel → Repository shape). The
 * reader consumes the format-blind [Book] model, so nothing here knows or cares that the source was
 * an EPUB.
 */
class ReaderViewModel(
    private val repository: CitationRepository,
    /**
     * The read-aloud narrator, when the host supplied one.
     *
     * Nullable because listening needs a `Context` and the ViewModel deliberately has none: the
     * activity builds the narrator and hands it over. A null one leaves every control below inert
     * and the reader exactly as it was before speech existed, which is also what makes the rest of
     * this class testable without an Android runtime.
     */
    private val narrator: Narrator? = null
) : ViewModel() {

    val books: StateFlow<List<CitationRepository.BookSummary>> =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every captured note, newest first, for the Notes screen. */
    val notes: StateFlow<List<Note>> =
        repository.notes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The most recently opened book, driving the Read tab's resume card. */
    val lastOpened: StateFlow<CitationRepository.BookSummary?> =
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
    private val _openBook = MutableStateFlow<Book?>(null)
    private val _chapterOrdinal = MutableStateFlow(0)

    private val _position = MutableStateFlow(0 to 0)

    /** The pace to estimate the open book with; reloaded whenever a book is opened. */
    private val _pace = MutableStateFlow(ReadingPace())

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

    /**
     * The reader moved. [charOffset] is a **canonical** offset, so both reading modes report the
     * same thing and the numbers do not jump when you switch between them.
     *
     * Forward movement is banked toward the pace estimate; moving backwards is re-reading and
     * measures nothing. A large forward jump (following a search hit, tapping the contents) is
     * banked too, and then discarded by [ReadingPace] for being implausibly fast — which is the
     * right place for that judgement, since only it knows what a plausible rate looks like.
     */
    fun onPositionChanged(chapterOrdinal: Int, charOffset: Int) {
        val book = _openBook.value ?: return
        val before = ReadingProgress.at(book, _position.value.first, _position.value.second)
        val after = ReadingProgress.at(book, chapterOrdinal, charOffset)
        val advanced = after.charactersRead - before.charactersRead
        if (advanced > 0) paceCharacters += advanced
        _position.value = chapterOrdinal to charOffset
    }

    /** Characters covered since the last pace report, paired with the meter's engaged time. */
    private var paceCharacters = 0

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

    private val _perBookSettings = MutableStateFlow(false)

    /** Whether the open book keeps settings of its own rather than following the global ones. */
    val perBookSettings: StateFlow<Boolean> = _perBookSettings.asStateFlow()

    private val _readerFonts = MutableStateFlow(repository.readerFonts())

    /** Fonts the reader has added, each under the name it goes by, for the picker to offer again. */
    val readerFonts: StateFlow<List<ReaderFont>> = _readerFonts.asStateFlow()

    private fun refreshPerBookFlag(bookKey: String?) {
        if (bookKey == null) {
            _perBookSettings.value = false
            return
        }
        viewModelScope.launch { _perBookSettings.value = repository.hasOwnReaderSettings(bookKey) }
    }

    /**
     * Change a setting.
     *
     * Writes to whichever scope the reader is currently editing: this book, when it has been given
     * its own settings, otherwise the global set. That is what makes the "just this book" switch
     * mean something afterwards rather than only at the moment it is flipped.
     */
    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val current = settings.value
        val bookKey = _openBook.value?.key?.toString()
        val scope = if (_perBookSettings.value) bookKey else null
        viewModelScope.launch { repository.saveReaderSettings(transform(current), scope) }
    }

    /**
     * Give the open book its own settings, or put it back on the global ones.
     *
     * Turning it on forks whatever the book is being read with right now, so nothing visibly
     * changes at the moment of the switch — it only stops following the global set from here.
     */
    fun setPerBookSettings(own: Boolean) {
        val bookKey = _openBook.value?.key?.toString() ?: return
        val current = settings.value
        viewModelScope.launch {
            if (own) repository.saveReaderSettings(current, bookKey) else repository.clearReaderSettings(bookKey)
            _perBookSettings.value = own
        }
    }

    /**
     * Recolour a highlight.
     *
     * Not a re-post: what a note *says* travels on the sync wire, what its mark looks like on your
     * page does not. Recolouring one is filing, like tagging it.
     */
    fun setNoteHighlight(noteKey: String, color: HighlightColor) {
        viewModelScope.launch { repository.setNoteHighlight(noteKey, color) }
    }

    /**
     * Store a font the reader picked and set the book to use it.
     *
     * [pickedName] is the document's own name, which becomes the font's first label — what the file
     * is called on disk here is a digest of its bytes, and no reader would recognise a face by it.
     */
    fun addReaderFont(bytes: ByteArray, extension: String, pickedName: String? = null) {
        viewModelScope.launch {
            val path = repository.storeReaderFont(bytes, extension, pickedName)
            if (path == null) {
                _status.value = "Couldn’t read that font file."
                return@launch
            }
            val fonts = repository.readerFonts()
            _readerFonts.value = fonts
            updateSettings { it.copy(typeface = ReaderTypeface.CUSTOM, customFontPath = path) }
            _status.value = "Reading in ${fonts.firstOrNull { it.path == path }?.name ?: "your font"}."
        }
    }

    /**
     * Rename a font the reader added.
     *
     * A rename touches the label only, never the file: the font's name on disk is its content digest
     * and every book already set in it refers to it by that path.
     */
    fun renameReaderFont(path: String, name: String) {
        viewModelScope.launch {
            if (!repository.renameReaderFont(path, name)) {
                _status.value = "Couldn’t rename that font."
                return@launch
            }
            _readerFonts.value = repository.readerFonts()
        }
    }

    /**
     * Remove a font the reader added.
     *
     * If it was the face being read in, the setting goes back to sans rather than being left
     * pointing at a file that is gone. Other books set in it are not rewritten — they fall back to
     * sans when rendered, which is what has always happened to a font that vanished, and rewriting
     * every book's settings to chase one deleted file would be a worse trade.
     */
    fun deleteReaderFont(path: String) {
        viewModelScope.launch {
            val name = _readerFonts.value.firstOrNull { it.path == path }?.name
            if (!repository.deleteReaderFont(path)) {
                _status.value = "Couldn’t remove that font."
                return@launch
            }
            _readerFonts.value = repository.readerFonts()
            if (settings.value.customFontPath == path) {
                updateSettings {
                    it.copy(
                        customFontPath = null,
                        typeface = if (it.typeface == ReaderTypeface.CUSTOM) ReaderTypeface.SANS else it.typeface
                    )
                }
            }
            _status.value = name?.let { "Removed $it." } ?: "Font removed."
        }
    }

    /**
     * Whether the screen stays on while reading. Read off the settings so it covers every reader
     * track, not just the flowing one.
     */
    val keepAwake: StateFlow<Boolean> =
        settings.map { it.keepAwake }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setKeepAwake(on: Boolean) = updateSettings { it.copy(keepAwake = on) }

    private val _pageTurns = MutableStateFlow(0L)

    /**
     * A ticking counter of page turns asked for by something other than a gesture — a volume key.
     *
     * A counter rather than the action itself, because two presses of the *same* key must both be
     * seen: a flow carrying `NEXT_PAGE` twice in a row would not emit the second time, and the page
     * would silently refuse to turn.
     */
    val pageTurns: StateFlow<Long> = _pageTurns.asStateFlow()

    private var pendingPageTurn: VolumeKeys.Action? = null

    /**
     * A volume key was pressed while reading. Returns true when the reader consumed it — so the
     * system's volume UI stays out of the way when the keys are turning pages, and behaves exactly
     * as normal when they are not.
     */
    fun onVolumeKey(volumeUp: Boolean): Boolean {
        val action = VolumeKeys.action(volumeUp, settings.value)
        if (action == VolumeKeys.Action.IGNORE) return false
        pendingPageTurn = action
        _pageTurns.value = _pageTurns.value + 1
        return true
    }

    /**
     * Take the pending turn, if any.
     *
     * The reading surface acts on it rather than the ViewModel, because only the surface knows where
     * the page boundaries are — the paginator's page starts live in the composition that measured
     * them.
     */
    fun consumePageTurn(): VolumeKeys.Action? = pendingPageTurn.also { pendingPageTurn = null }

    // --- In-book search ---------------------------------------------------------------------------

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchHits = MutableStateFlow<List<BookSearch.Hit>>(emptyList())
    val searchHits: StateFlow<List<BookSearch.Hit>> = _searchHits.asStateFlow()

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    /**
     * Hits in the chapter on screen, so the words you searched for are lit up when you land on
     * them. Canonical ranges — the reader converts them like any other highlight.
     */
    val searchRanges: StateFlow<List<IntRange>> =
        combine(_searchHits, _chapterOrdinal, _searchOpen) { hits, ordinal, open ->
            if (!open) emptyList() else hits.filter { it.chapterOrdinal == ordinal }.map { it.range }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun openSearch() { _searchOpen.value = true }

    fun closeSearch() {
        _searchOpen.value = false
        _searchQuery.value = ""
        _searchHits.value = emptyList()
    }

    /**
     * Run a search over the open book. Synchronous over the in-memory book — one person's book is
     * small, so there is no index to build and no reason to make the caller wait on a coroutine.
     */
    fun search(query: String) {
        _searchQuery.value = query
        val book = _openBook.value
        _searchHits.value = if (book == null) emptyList() else BookSearch.search(book, query)
    }

    /** Land on a hit: its chapter, at its offset, with the match still lit. */
    fun goToHit(hit: BookSearch.Hit) {
        pendingScrollChapter = hit.chapterOrdinal
        pendingScrollOffset = hit.offset
        goToChapter(hit.chapterOrdinal)
    }

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

    /**
     * Save this place, or remove the one already saved here.
     *
     * [charOffset] comes from the reader's viewport rather than the stored position, so a bookmark
     * marks the page you are looking at rather than the last place a debounced save happened to
     * land.
     */
    fun toggleBookmark(charOffset: Int) {
        val book = _openBook.value ?: return
        if (book.key == null) return
        viewModelScope.launch {
            val ordinal = _chapterOrdinal.value
            val existing = Bookmarks.existingAt(bookmarks.value, ordinal, charOffset)
            if (existing != null) {
                repository.deleteBookmark(existing.key.toString())
                _status.value = "Bookmark removed."
            } else {
                val saved = repository.addBookmark(book, ordinal, charOffset)
                _status.value = "Bookmarked: ${saved.display.take(60)}"
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.deleteBookmark(bookmark.key.toString()) }
    }

    fun setBookmarkLabel(bookmark: Bookmark, label: String) {
        viewModelScope.launch { repository.setBookmarkLabel(bookmark.key.toString(), label) }
    }

    /**
     * Go to a bookmark, landing where its frozen line is *now*. A line that has since been deleted
     * opens its chapter rather than jumping to an offset that no longer means anything.
     */
    fun goToBookmark(bookmark: Bookmark) {
        val book = _openBook.value ?: return
        when (val target = Bookmarks.resolve(bookmark, book)) {
            is Bookmarks.Target.Found -> {
                pendingScrollChapter = target.chapterOrdinal
                pendingScrollOffset = target.charOffset
                goToChapter(target.chapterOrdinal)
                if (!target.exact) _status.value = "That passage was edited — landed as close as possible."
            }
            is Bookmarks.Target.ChapterOnly -> {
                goToChapter(target.chapterOrdinal)
                _status.value = "That passage is gone; opened the chapter instead."
            }
            Bookmarks.Target.Unavailable ->
                _status.value = "That chapter isn’t available yet."
        }
    }

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

    private val _libraryFilter = MutableStateFlow(LibraryFilter())
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    private val _librarySort = MutableStateFlow(LibrarySort.RECENT)
    val librarySort: StateFlow<LibrarySort> = _librarySort.asStateFlow()

    private val _libraryGrid = MutableStateFlow(true)
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

    fun setLibraryQuery(text: String) {
        _libraryFilter.value = _libraryFilter.value.copy(query = text)
    }

    fun setLibrarySort(sort: LibrarySort) { _librarySort.value = sort }

    fun setLibraryGrid(grid: Boolean) { _libraryGrid.value = grid }

    /** Tapping the active shelf clears it; tapping another switches to it. */
    fun toggleCollectionFilter(id: String?) {
        val current = _libraryFilter.value
        _libraryFilter.value = current.copy(collectionId = if (current.collectionId == id) null else id)
    }

    fun toggleSubjectFilter(subject: String) {
        val current = _libraryFilter.value
        _libraryFilter.value = current.copy(subject = if (current.subject == subject) null else subject)
    }

    fun toggleFavoritesFilter() {
        val current = _libraryFilter.value
        _libraryFilter.value = current.copy(favoritesOnly = !current.favoritesOnly)
    }

    fun clearLibraryFilter() {
        _libraryFilter.value = LibraryFilter(query = _libraryFilter.value.query)
    }

    /** The cover file for a book, or null — the shelf decodes it directly, downsampled. */
    fun coverFile(bookKey: String): java.io.File? = repository.coverFile(bookKey)

    fun setFavorite(bookKey: String, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(bookKey, favorite) }
    }

    fun setReadingState(bookKey: String, state: com.citation.core.sync.ReadingState) {
        viewModelScope.launch { repository.setReadingState(bookKey, state) }
    }

    /**
     * Re-read a book from the file it was imported from, so an older import picks up structure,
     * a cover and shelf metadata the parser can now see. Only chapters whose text is unchanged are
     * touched, so notes stay anchored exactly where they were.
     */
    fun refreshFromFile(bookKey: String) {
        viewModelScope.launch {
            _status.value = when (val result = repository.refreshFromFile(bookKey)) {
                is CitationRepository.RefreshResult.Refreshed ->
                    if (result.unchanged == 0) "Refreshed ${result.chapters} chapters from the file."
                    else "Refreshed ${result.chapters} chapters; ${result.unchanged} were left as they were."
                CitationRepository.RefreshResult.NotRefreshable -> "This one has no stored file to re-read."
                CitationRepository.RefreshResult.FileMissing -> "The original file isn’t in the store any more."
                CitationRepository.RefreshResult.Unreadable -> "Couldn’t re-read the file."
            }
        }
    }

    fun createCollection(name: String) {
        viewModelScope.launch { repository.createCollection(name) }
    }

    fun renameCollection(id: String, name: String) {
        viewModelScope.launch { repository.renameCollection(id, name) }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            repository.deleteCollection(id)
            if (_libraryFilter.value.collectionId == id) toggleCollectionFilter(null)
        }
    }

    fun setCollectionMembership(collectionId: String, bookKey: String, member: Boolean) {
        viewModelScope.launch {
            if (member) repository.addToCollection(collectionId, bookKey)
            else repository.removeFromCollection(collectionId, bookKey)
        }
    }

    // --- Catalog browsing -----------------------------------------------------------------------
    //
    // The acquisition half of the library. Browsing is a native surface rather than a WebView on
    // someone's site, because the entries are data: that is what lets a tap become a download that
    // lands in the library with its series, subjects and blurb already attached.

    val catalogs: StateFlow<List<CatalogSource>> =
        repository.catalogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _catalogPage = MutableStateFlow<CatalogPage?>(null)
    /** The catalog page on screen, or null when the browser is closed. */
    val catalogPage: StateFlow<CatalogPage?> = _catalogPage.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    private val _catalogError = MutableStateFlow<String?>(null)
    /** Why the last catalog fetch failed, phrased for a person. Cleared on the next success. */
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** Previous pages, so Back walks up a catalog the way it walked down. */
    private val catalogStack = ArrayDeque<CatalogPage>()

    val canGoBackInCatalog: Boolean get() = catalogStack.isNotEmpty()

    /** Make sure the presets exist before the catalogs list is first shown. */
    fun ensureCatalogs() {
        viewModelScope.launch { repository.seedCatalogsIfEmpty() }
    }

    private val _catalogsOpen = MutableStateFlow(false)
    /** Whether the catalogs surface has taken over the shell (it preempts, like the readers do). */
    val catalogsOpen: StateFlow<Boolean> = _catalogsOpen.asStateFlow()

    fun openCatalogs() {
        _catalogsOpen.value = true
        ensureCatalogs()
    }

    fun closeCatalogs() {
        closeCatalog()
        _catalogsOpen.value = false
    }

    fun openCatalog(source: CatalogSource) {
        catalogStack.clear()
        loadCatalog(source, null, push = false)
    }

    /** Follow a link within the catalog currently open. */
    fun followCatalogLink(url: String) {
        val current = _catalogPage.value ?: return
        loadCatalog(current.source, url, push = true)
    }

    fun retryCatalog() {
        val current = _catalogPage.value
        if (current != null) loadCatalog(current.source, current.url, push = false)
    }

    private fun loadCatalog(source: CatalogSource, url: String?, push: Boolean) {
        viewModelScope.launch {
            _catalogLoading.value = true
            _catalogError.value = null
            val previous = _catalogPage.value
            when (val result = repository.openCatalog(source, url)) {
                is OpdsClient.Result.Success -> {
                    if (push && previous != null) catalogStack.addLast(previous)
                    _catalogPage.value = result.value
                }
                else -> {
                    _catalogError.value = catalogFailure(result)
                    // Keep whatever page was already up, so a failed step does not empty the screen.
                    if (previous == null) _catalogPage.value = CatalogPage(source, url ?: source.rootUrl, emptyFeed(source, url))
                }
            }
            _catalogLoading.value = false
        }
    }

    fun searchCatalog(terms: String) {
        val current = _catalogPage.value ?: return
        if (terms.isBlank()) return
        viewModelScope.launch {
            _catalogLoading.value = true
            _catalogError.value = null
            when (val result = repository.searchCatalog(current, terms)) {
                is OpdsClient.Result.Success -> {
                    catalogStack.addLast(current)
                    _catalogPage.value = result.value
                }
                else -> _catalogError.value = catalogFailure(result)
            }
            _catalogLoading.value = false
        }
    }

    /** Step back up the catalog. Returns false when there is nowhere left to go. */
    fun catalogBack(): Boolean {
        val previous = catalogStack.removeLastOrNull() ?: return false
        _catalogPage.value = previous
        _catalogError.value = null
        return true
    }

    fun closeCatalog() {
        catalogStack.clear()
        _catalogPage.value = null
        _catalogError.value = null
        catalogThumbnails.clear()
        catalogThumbnailMisses.clear()
    }

    private fun emptyFeed(source: CatalogSource, url: String?) =
        OpdsFeed(title = source.name, id = null, links = emptyList(), entries = emptyList(), url = url ?: source.rootUrl)

    private fun catalogFailure(result: OpdsClient.Result<*>): String = when (result) {
        is OpdsClient.Result.Unauthorized -> "This catalog needs a sign-in. Add one in its settings."
        is OpdsClient.Result.NotACatalog ->
            if (result.looksLikeHtml) "That address is a web page, not a catalog. Try adding /opds to it."
            else "The server didn’t answer with a catalog."
        is OpdsClient.Result.Unreachable -> "Couldn’t reach it: ${result.message}"
        is OpdsClient.Result.HttpError -> "The server said ${result.code}."
        is OpdsClient.Result.Unsupported -> "This catalog doesn’t offer that."
        is OpdsClient.Result.Success -> ""
    }

    // Thumbnails are fetched once per browsing session and dropped when the browser closes. A
    // catalog page is a few dozen covers; caching them is what keeps scrolling back up from
    // re-downloading the shelf, and clearing on close is what keeps that from growing unbounded.
    // Concurrent because a screenful of covers all start loading at once; misses are remembered
    // separately so a cover the server doesn't have is asked for once, not on every recomposition.
    private val catalogThumbnails = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val catalogThumbnailMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun catalogThumbnail(url: String): ByteArray? {
        catalogThumbnails[url]?.let { return it }
        if (url in catalogThumbnailMisses) return null
        val source = _catalogPage.value?.source ?: return null
        val bytes = repository.catalogImage(source, url)
        if (bytes == null) catalogThumbnailMisses.add(url) else catalogThumbnails[url] = bytes
        return bytes
    }

    private val _acquiring = MutableStateFlow<Set<String>>(emptySet())
    /** Entry ids currently downloading, so their rows can show it. */
    val acquiring: StateFlow<Set<String>> = _acquiring.asStateFlow()

    /** Download a catalog entry into the library. */
    fun acquire(entry: OpdsEntry) {
        val source = _catalogPage.value?.source ?: return
        val id = entry.id ?: entry.title
        if (id in _acquiring.value) return
        viewModelScope.launch {
            _acquiring.value = _acquiring.value + id
            val result = repository.acquire(source, entry)
            _status.value = when (result) {
                is CitationRepository.AcquireResult.Added -> "Added “${result.title}” to your library."
                is CitationRepository.AcquireResult.AlreadyHave -> "“${result.title}” is already in your library."
                is CitationRepository.AcquireResult.UnsupportedFormat ->
                    "Citation can’t read ${result.label} files yet."
                is CitationRepository.AcquireResult.Failed -> "Couldn’t download it: ${result.reason}"
            }
            _acquiring.value = _acquiring.value - id
        }
    }

    fun addCatalog(name: String, url: String, username: String, password: String) {
        viewModelScope.launch {
            val added = repository.addCatalog(name, url, username.takeIf { it.isNotBlank() }, password)
            _status.value = "Added “${added.name}”."
        }
    }

    fun deleteCatalog(id: String) {
        viewModelScope.launch { repository.deleteCatalog(id) }
    }

    fun setCatalogCredentials(id: String, username: String, password: String) {
        viewModelScope.launch { repository.setCatalogCredentials(id, username, password) }
    }

    // --- Notes retrieval: free-text search + tag facet, and Markdown export ---------------------

    private val _query = MutableStateFlow("")
    /** The live search text over notes (matches body, frozen snapshot, title/author, and tags). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _activeTag = MutableStateFlow<String?>(null)
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

    fun setQuery(text: String) { _query.value = text }

    /** Toggle a tag filter: tapping the active tag clears it, tapping another switches to it. */
    fun toggleTag(tag: String) {
        _activeTag.value = if (_activeTag.value == tag) null else tag
    }

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
    private var pendingScrollChapter = -1
    private var pendingScrollOffset = 0

    // A pending restore whose offset is *canonical text*, not whatever the open reading mode stores.
    // It exists because the voice only ever knows characters: resuming where listening left off has
    // to be resolved against the rendered chapter (to a page, or to a line's y) rather than handed
    // to `scrollTo` as though it were pixels. Kept beside the older channel rather than replacing
    // it, so an ordinary reading resume behaves exactly as it did.
    private var pendingCanonicalChapter = -1
    private var pendingCanonicalOffset = 0

    // The chapter a *backward* page turn is entering, which must open at its end rather than its
    // start. Only the reader knows where that chapter's last page begins — it depends on the live
    // typography and viewport — so the intent is recorded here and the reader resolves it, exactly
    // like pendingScroll above. Consumed once, so a later turn doesn't get pulled back to the end.
    private var pendingEndChapter = -1

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // Set only when an import arriving from *outside* the app fails; drawn as an alert over the
    // reader (see reportImportProblem), because such an import has no picker screen to report back to.
    private val _importAlert = MutableStateFlow<String?>(null)
    val importAlert: StateFlow<String?> = _importAlert.asStateFlow()

    // --- Engaged-reading meter -----------------------------------------------------------------
    // Measures only *active* reading time: it accrues between progress signals (page turns, scrolls,
    // chapter advances), each interval capped, and pauses when the reader isn't visible. So leaving
    // the app open on a page can't inflate the count — which is what lets reading be rewarded without
    // an output cap. Drained to whole minutes and posted up the mailbox as telemetry.
    private val readingMeter = ReadingMeter()
    private var readingBookKey: String? = null
    // Sub-minute engaged time carried across a pause/resume so brief backgrounding doesn't lose it.
    private var readingRemainderMillis = 0L

    /** Start metering engaged reading for [bookKey]; report + close any prior book's session first. */
    /** Reset the position/pace state for a newly opened book, and load what we know of its pace. */
    private fun beginPositionTracking(bookKey: String?, chapterOrdinal: Int, charOffset: Int) {
        refreshPerBookFlag(bookKey)
        _position.value = chapterOrdinal to charOffset
        paceCharacters = 0
        _pace.value = ReadingPace()
        if (bookKey == null) return
        viewModelScope.launch { _pace.value = repository.paceFor(bookKey) }
    }

    private fun startReadingSession(bookKey: String?) {
        bookKey ?: return
        if (readingBookKey != null && readingBookKey != bookKey) endReadingSession()
        readingBookKey = bookKey
        readingMeter.resume()
    }

    /** A reading-progress signal (page turn / scroll / chapter advance) from any reader track. */
    fun onReadingProgress() {
        if (readingBookKey != null) readingMeter.progress()
    }

    /** Reader became visible again (lifecycle resume): keep accruing. */
    fun onReaderVisible() {
        if (readingBookKey != null) readingMeter.resume()
    }

    /**
     * Reader went to the background (lifecycle stop): bank + report engaged time, keep the session.
     *
     * Also the one place [SpeechSettings.continueInBackground] is enforced. Off, the voice is a
     * feature of the page and stops when you leave it; on — the default, and the whole point of
     * listening — it carries on with the screen locked, held up by the foreground service.
     */
    fun onReaderHidden() {
        reportReading()
        if (!speechSettings.value.continueInBackground && narration.value.isActive) pauseAloud()
    }

    /** End the current reading session (book closed): report, then clear and drop the sub-minute tail. */
    private fun endReadingSession() {
        reportReading()
        readingBookKey = null
        readingRemainderMillis = 0
    }

    /** Drain the meter to whole engaged minutes and post telemetry; carry the sub-minute remainder. */
    private fun reportReading() {
        val key = readingBookKey ?: return
        readingMeter.pause()
        val total = readingRemainderMillis + readingMeter.flushMillis()
        readingRemainderMillis = total % 60_000L
        val minutes = (total / 60_000L).toInt()

        // The same engaged milliseconds that make the telemetry honest make the pace honest: this
        // is time the meter already refused to credit if you had stepped away.
        val characters = paceCharacters
        paceCharacters = 0
        if (characters > 0 && total > 0) {
            viewModelScope.launch {
                repository.recordPace(key, characters, total)
                _pace.value = repository.paceFor(key)
            }
        }

        if (minutes > 0) viewModelScope.launch { repository.recordReadingTelemetry(key, minutes) }
    }

    // Non-null while a Royal Road serial is open, so page turns can slide its prefetch buffer.
    private var openRrFictionId: Long? = null

    // The PDF paged reader and the O'Reilly read-in-place reader are separate tracks from the
    // flowing reader; when one is set the UI shows that track instead.
    private val _pdfSession = MutableStateFlow<CitationRepository.PdfSession?>(null)
    val pdfSession: StateFlow<CitationRepository.PdfSession?> = _pdfSession.asStateFlow()

    private val _oreillySession = MutableStateFlow<CitationRepository.OreillySession?>(null)
    val oreillySession: StateFlow<CitationRepository.OreillySession?> = _oreillySession.asStateFlow()

    private val _kindleSession = MutableStateFlow<CitationRepository.KindleSession?>(null)
    val kindleSession: StateFlow<CitationRepository.KindleSession?> = _kindleSession.asStateFlow()

    // Non-null while browsing the O'Reilly catalog (proxied through your library); the catalog surface
    // preempts the home shell, mirroring Browse Royal Road.
    private val _oreillyCatalog = MutableStateFlow<CitationRepository.OreillyCatalog?>(null)
    val oreillyCatalog: StateFlow<CitationRepository.OreillyCatalog?> = _oreillyCatalog.asStateFlow()

    // Non-null while browsing your Kindle library on read.amazon.com; the browse surface preempts the
    // home shell, exactly like the O'Reilly catalog — you skim the shelf and tap a book to open it.
    private val _kindleLibrary = MutableStateFlow<CitationRepository.KindleLibrary?>(null)
    val kindleLibrary: StateFlow<CitationRepository.KindleLibrary?> = _kindleLibrary.asStateFlow()

    /**
     * Imports an EPUB. [openAfter] is set when the file arrived as an "open this book" intent from
     * another app: there the point of the tap was to read it, so the import lands in the reader
     * rather than in a status line the user would have to go looking for.
     */
    fun importEpub(bytes: ByteArray, openAfter: Boolean = false) {
        viewModelScope.launch {
            val book = repository.importEpub(bytes)
            _status.value = if (book != null) {
                "Imported “${book.metadata.title}” (${book.chapters.size} chapters)"
            } else {
                "That file didn’t parse as an EPUB."
            }
            if (openAfter) {
                val key = book?.key
                if (key != null) open(key.toString()) else _importAlert.value = "That file didn’t parse as an EPUB."
            }
        }
    }

    fun open(bookKey: String) {
        viewModelScope.launch {
            // Read cache staleness from the *prior* open time, before markOpened restamps it to now.
            val warmCacheStale = repository.oreillyWarmCacheStale(bookKey)
            repository.markOpened(bookKey) // stamp for the Read tab's resume
            when (repository.sourceTypeOf(bookKey)) {
                SourceType.PDF -> {
                    // Pages are the ground truth, so a PDF always opens paged; the reader offers the
                    // reflowed text track when one exists (and can build it on demand when it doesn't).
                    _pdfInitialPage.value = 0
                    _pdfFlowReady.value = repository.hasPdfFlow(bookKey)
                    _pdfSession.value = repository.pdfSession(bookKey)
                }
                SourceType.OREILLY ->
                    _oreillySession.value = repository.oreillySession(bookKey)?.copy(purgeWarmCache = warmCacheStale)
                SourceType.KINDLE -> {
                    _kindleSession.value = repository.kindleSession(bookKey)
                    _kindleSession.value?.bookKey?.let { repository.beginReaderContext(it) }
                }
                else -> {
                    val result = repository.openBook(bookKey)
                    openRrFictionId = result?.rrFictionId
                    _openBook.value = result?.book
                    // Land where you left off: restore the saved chapter, and stage the scroll offset
                    // for the reader to apply on first paint. Royal Road needs its buffer slid to the
                    // resumed chapter, so route through goToChapter for that side.
                    val lastIndex = (result?.book?.chapters?.lastIndex ?: 0).coerceAtLeast(0)
                    // Two places may have been recorded — where you last read, and where the voice
                    // got to while the app was in your pocket. Open at whichever is more recent.
                    val place = Resume.choose(
                        reading = result?.let { SavedPlace(it.chapterOrdinal, it.charOffset, it.positionSavedAt) },
                        listening = result?.listening
                    )
                    val savedChapter = (place?.chapterOrdinal ?: 0).coerceIn(0, lastIndex)
                    val savedOffset = place?.charOffset ?: 0
                    pendingScrollChapter = -1
                    pendingScrollOffset = 0
                    pendingCanonicalChapter = -1
                    pendingCanonicalOffset = 0
                    // A listened place is *always* a canonical character offset, so it is staged on
                    // the channel the reader resolves rather than the one the scroll reader treats
                    // as pixels. A read place is staged exactly as it always was.
                    if (place?.canonical == true) {
                        pendingCanonicalChapter = savedChapter
                        pendingCanonicalOffset = savedOffset
                    } else {
                        pendingScrollChapter = savedChapter
                        pendingScrollOffset = savedOffset
                    }
                    pendingEndChapter = -1
                    _chapterOrdinal.value = savedChapter
                    beginPositionTracking(bookKey, savedChapter, savedOffset)
                    if (result?.rrFictionId != null && savedChapter > 0) goToChapter(savedChapter)
                }
            }
            startReadingSession(bookKey)
        }
    }

    fun importPdf(bytes: ByteArray, title: String) {
        viewModelScope.launch {
            val key = repository.importPdf(bytes, title)
            repository.markOpened(key)
            _status.value = "Imported PDF “$title”."
            _pdfSession.value = repository.pdfSession(key)
            startReadingSession(key)
            // The pages are readable immediately; the reflowed text track is built behind them so
            // "Read as text" is ready by the time you look for it (and says so when it isn't).
            if (!repository.hasPdfFlow(key)) reflow(key, announce = true)
        }
    }

    // --- PDF: the two tracks over one file ------------------------------------------------------
    // A PDF is a picture of glyphs, so the paged render can't be selected from. Reflowing extracts its
    // text into the same format-blind Book the flowing reader already draws, which is what makes a PDF
    // quotable at all. The reflow is derived: the file and its pages remain the ground truth, and the
    // reader switches between them without losing your place.

    private val _pdfFlowReady = MutableStateFlow(false)
    /** Whether the open PDF has a reflowed text track available (drives the reader's track toggle). */
    val pdfFlowReady: StateFlow<Boolean> = _pdfFlowReady.asStateFlow()

    private val _pdfInitialPage = MutableStateFlow(0)
    /** The page the paged reader opens on — carried over when arriving from the text track. */
    val pdfInitialPage: StateFlow<Int> = _pdfInitialPage.asStateFlow()

    private val _reflowing = MutableStateFlow(false)
    /** True while a PDF's text is being extracted — the toggle shows progress rather than nothing. */
    val reflowing: StateFlow<Boolean> = _reflowing.asStateFlow()

    /** Extract + reflow [bookKey], reporting the outcome. Returns true when a text track now exists. */
    private suspend fun reflow(bookKey: String, announce: Boolean): Boolean {
        if (_reflowing.value) return false // an extraction is already running; don't start a second
        _reflowing.value = true
        val result = try { repository.reflowPdf(bookKey) } finally { _reflowing.value = false }
        val ready = result is CitationRepository.ReflowResult.Reflowed
        _pdfFlowReady.value = ready
        if (announce) {
            _status.value = when (result) {
                is CitationRepository.ReflowResult.Reflowed ->
                    "Text extracted — ${result.pages} page${if (result.pages == 1) "" else "s"} readable as text."
                CitationRepository.ReflowResult.NoTextLayer ->
                    "No text layer in this PDF (it's a scan) — pages only."
                CitationRepository.ReflowResult.Unreadable ->
                    "Couldn't read that PDF's text."
            }
        }
        return ready
    }

    /**
     * Switch the open PDF from rendered pages to its reflowed text, landing on the chapter that holds
     * the page you were looking at. Extracts on demand if the track isn't built yet; a scan with no
     * text layer says so and stays on the pages.
     */
    fun readPdfAsText(fromPage: Int) {
        val session = _pdfSession.value ?: return
        viewModelScope.launch {
            if (!repository.hasPdfFlow(session.bookKey) && !reflow(session.bookKey, announce = true)) return@launch
            val result = repository.openBook(session.bookKey) ?: return@launch
            val book = result.book
            val chapter = PdfFlow.chapterForPage(book.chapters, fromPage)
            _openBook.value = book
            _chapterOrdinal.value = chapter
            pendingScrollChapter = chapter
            pendingScrollOffset = 0
            _pdfFlowReady.value = true
            _pdfSession.value = null // the flowing reader takes over the screen
        }
    }

    /**
     * Switch the open reflowed PDF back to its rendered pages — the ground truth, for the figure or
     * table the reflow flattened. [nearOffset] is where the flowing reader was looking, so the paged
     * view opens on that same page.
     */
    fun readPdfAsPages(nearOffset: Int) {
        val book = _openBook.value ?: return
        val bookKey = book.key?.toString() ?: return
        if (book.metadata.source != SourceType.PDF) return
        val chapter = book.chapterAt(_chapterOrdinal.value)
        val page = chapter?.let { PdfFlow.pageOf(it, nearOffset) } ?: 0
        // Page first, then the session: the paged reader reads the landing page as it composes.
        _pdfInitialPage.value = page
        _openBook.value = null
        _pdfSession.value = repository.pdfSession(bookKey)
    }

    /**
     * Import a Kindle notebook export (the HTML you get from "Export notebook"). Each highlight/note
     * becomes a provisional capture, promotable to the real book when you add it properly.
     */
    fun importKindleNotebook(html: String) {
        viewModelScope.launch {
            val count = repository.importKindleNotebook(html)
            _status.value = when {
                count == null -> "That didn’t look like a Kindle notebook export."
                count == 0 -> "No highlights found in that export."
                else -> "Imported $count Kindle highlight${if (count == 1) "" else "s"}."
            }
        }
    }

    // --- O'Reilly library access (proxy host + encrypted card/PIN) ------------------------------

    private val _oreillyConfig = MutableStateFlow(
        OreillyAccess.Config(com.citation.core.oreilly.OreillyLibraryProxy.MID_CONTINENT_HOST, false)
    )
    /** Drives the Settings section: the library proxy host and whether a card/PIN are on file. */
    val oreillyConfig: StateFlow<OreillyAccess.Config> = _oreillyConfig.asStateFlow()

    init {
        // Load the stored access config off the constructor's path (Keystore-backed read).
        viewModelScope.launch { _oreillyConfig.value = repository.oreillyAccessConfig() }
    }

    /**
     * Save the library proxy host and, when both are given, the card + PIN (encrypted). A blank card
     * or PIN leaves any stored credential untouched, so re-saving just the host is safe.
     */
    fun saveOreillyAccess(proxyHost: String, card: String, pin: String) {
        viewModelScope.launch {
            repository.setOreillyProxyHost(proxyHost)
            if (card.isNotBlank() && pin.isNotBlank()) repository.setOreillyCredentials(card, pin)
            _oreillyConfig.value = repository.oreillyAccessConfig()
            _status.value = "O'Reilly library access saved."
        }
    }

    /** Forget the stored library card + PIN (keeps the proxy host). */
    fun clearOreillyCredentials() {
        viewModelScope.launch {
            repository.clearOreillyCredentials()
            _oreillyConfig.value = repository.oreillyAccessConfig()
            _status.value = "Cleared saved library card + PIN."
        }
    }

    fun addOreillyBook(bookId: String, title: String) {
        viewModelScope.launch {
            val key = repository.addOreillyBook(bookId, title)
            repository.markOpened(key)
            _oreillySession.value = repository.oreillySession(key)
            repository.beginReaderContext(key)
            startReadingSession(key)
        }
    }

    /** Open the O'Reilly catalog for browsing (proxied through your library, with your saved card/PIN). */
    fun browseOreilly() {
        viewModelScope.launch { _oreillyCatalog.value = repository.oreillyCatalog() }
    }

    /** Leave the O'Reilly catalog without opening anything. */
    fun closeOreillyCatalog() { _oreillyCatalog.value = null }

    /**
     * Open a book tapped in the O'Reilly catalog: register it read-in-place (deduped by id, so
     * re-browsing the same book reuses its library entry + notes) and hand off to the reader. This is
     * the O'Reilly counterpart to [openRoyalRoad] — browse, tap, and you're reading.
     */
    fun openOreillyFromCatalog(bookId: String, title: String) {
        viewModelScope.launch {
            val key = repository.addOreillyBook(bookId, title)
            repository.markOpened(key)
            _oreillyCatalog.value = null
            _oreillySession.value = repository.oreillySession(key)
            repository.beginReaderContext(key)
            startReadingSession(key)
        }
    }

    /** Capture a page-anchored note on the open PDF (quote located by the reader on that page). */
    fun capturePdfNote(page: Int, quote: String, body: String) {
        val session = _pdfSession.value ?: return
        viewModelScope.launch {
            val note = repository.capturePdfNote(session.bookKey, page, quote, body)
            _status.value = "Note ${note.key} captured on page ${page + 1}."
        }
    }

    fun closePdf() { endReadingSession(); _pdfSession.value = null }

    fun closeOreilly() {
        _oreillySession.value?.bookKey?.let { repository.endReaderContext(it) }
        endReadingSession()
        _oreillySession.value = null
    }

    /** Persist the O'Reilly reader's position so the next open lands one tap from your spot. */
    fun saveOreillyPosition(location: String) {
        onReadingProgress()
        val session = _oreillySession.value ?: return
        viewModelScope.launch { repository.saveExternalPosition(session.bookKey, location) }
    }

    /** Capture a note on the open O'Reilly book — your layer only (quote + location token). */
    fun captureOreillyNote(location: String, quote: String, body: String) {
        val session = _oreillySession.value ?: return
        viewModelScope.launch {
            val note = repository.captureExternalNote(session.bookKey, location, quote, body)
            _status.value = "Note ${note.key} captured on O'Reilly book."
        }
    }

    // --- Kindle read-in-place (read.amazon.com) -------------------------------------------------

    /**
     * Register a Kindle book by ASIN and open it read-in-place in the Cloud Reader. The counterpart to
     * [addOreillyBook] — add it, and you're reading (deduped by ASIN, so re-adding reuses its notes).
     */
    fun addKindleBook(asin: String, title: String) {
        viewModelScope.launch {
            val key = repository.addKindleBook(asin, title)
            repository.markOpened(key)
            _kindleSession.value = repository.kindleSession(key)
            repository.beginReaderContext(key)
            startReadingSession(key)
        }
    }

    /** Open your Kindle library on read.amazon.com to browse and pick a book (learns its ASIN + title). */
    fun browseKindle() {
        _kindleLibrary.value = repository.kindleLibrary()
    }

    /** Leave the Kindle library without opening anything. */
    fun closeKindleLibrary() { _kindleLibrary.value = null }

    /**
     * Open a book tapped in the Kindle library: register it read-in-place with the ASIN + learned title
     * (deduped by ASIN, so re-picking the same book reuses its notes) and hand off to the reader. The
     * Kindle counterpart to [openOreillyFromCatalog] — browse the shelf, tap, and you're reading.
     */
    fun openKindleFromLibrary(asin: String, title: String) {
        viewModelScope.launch {
            val key = repository.addKindleBook(asin, title)
            repository.markOpened(key)
            _kindleLibrary.value = null
            _kindleSession.value = repository.kindleSession(key)
            repository.beginReaderContext(key)
            startReadingSession(key)
        }
    }

    fun closeKindle() {
        _kindleSession.value?.bookKey?.let { repository.endReaderContext(it) }
        endReadingSession()
        _kindleSession.value = null
    }

    /** Persist the Kindle reader's last position label so the library shows where you were. */
    fun saveKindlePosition(location: String) {
        onReadingProgress()
        val session = _kindleSession.value ?: return
        viewModelScope.launch { repository.saveExternalPosition(session.bookKey, location) }
    }

    /**
     * Capture a note on the open Kindle book. `read.amazon.com` blocks copying the passage, so the
     * quote is normally unavailable: the reader's position label ([location], e.g. "Location 156 of
     * 3866") stands in as the citation. A real [quote] is still honoured when present — in case text
     * selection ever works — and only falls back to the location when left blank. The book is the
     * source; the annotation is your own.
     */
    fun captureKindleNote(location: String, quote: String, body: String) {
        val session = _kindleSession.value ?: return
        viewModelScope.launch {
            val cited = quote.ifBlank { location }
            val note = repository.captureExternalNote(session.bookKey, location, cited, body)
            _status.value = "Note ${note.key} captured on Kindle book."
        }
    }

    /**
     * Open a Royal Road serial *through the reader* — the WebView is only for skimming/catalog, so
     * even chapter 1 comes back as internal-model text here, identical to every later chapter. The
     * serial is registered as a sovereign book, so notes on it work like any other source.
     */
    fun openRoyalRoad(fictionId: Long) {
        viewModelScope.launch {
            _status.value = "Fetching Royal Road catalog…"
            runCatching { repository.openRoyalRoad(fictionId) }
                .onSuccess { book ->
                    openRrFictionId = fictionId
                    book.key?.let { repository.markOpened(it.toString()) }
                    _openBook.value = book
                    _chapterOrdinal.value = 0
                    _status.value = "Opened “${book.metadata.title}”."
                    startReadingSession(book.key?.toString())
                }
                .onFailure { _status.value = "Couldn’t open that Royal Road story." }
        }
    }

    fun favoriteRoyalRoad() {
        val fictionId = openRrFictionId ?: return
        viewModelScope.launch {
            repository.royalRoad.markFavorite(fictionId, true)
            _status.value = "Favourited — full backfill queued, kept indefinitely."
        }
    }

    /**
     * Open an Archive of Our Own work: download its official EPUB the first time (imported as an owned
     * snapshot), then open it through the reader like any owned book. The WebView is only for
     * finding the work; reading always happens on the parsed internal model.
     */
    fun openAo3(workId: Long) {
        viewModelScope.launch {
            _status.value = "Downloading from Archive of Our Own…"
            runCatching { repository.openAo3(workId) }
                .onSuccess { book ->
                    openRrFictionId = null
                    book.key?.let { repository.markOpened(it.toString()) }
                    _openBook.value = book
                    _chapterOrdinal.value = 0
                    _status.value = "Opened “${book.metadata.title}”."
                    startReadingSession(book.key?.toString())
                }
                .onFailure { _status.value = "Couldn’t download that Archive of Our Own work." }
        }
    }

    fun closeBook() {
        endReadingSession()
        openRrFictionId = null
        _openBook.value = null
        // A turn that never landed must not be inherited by the next book opened at that ordinal.
        pendingScrollChapter = -1
        pendingScrollOffset = 0
        pendingCanonicalChapter = -1
        pendingCanonicalOffset = 0
        pendingEndChapter = -1
        closeSearch()
        _perBookSettings.value = false
        _position.value = 0 to 0
        _pace.value = ReadingPace()
    }

    /**
     * Remove a book from the library. For a Royal Road serial this also un-favourites it and drops its
     * cached chapter bodies (the "uncache" the user wants); if it happens to be the one open in the
     * reader, the reader is closed too. Notes on it are kept (they hold their own frozen snapshots).
     */
    fun deleteBook(book: CitationRepository.BookSummary) = deleteBook(book.key, book.title)

    /** Remove a book by key, for surfaces that hold a library entry rather than a summary. */
    fun deleteBook(bookKey: String, title: String) {
        viewModelScope.launch {
            repository.deleteBook(bookKey)
            if (_openBook.value?.key?.toString() == bookKey) closeBook()
            _status.value = "Removed “$title”."
        }
    }

    fun goToChapter(ordinal: Int) {
        val book = _openBook.value ?: return
        val target = ordinal.coerceIn(0, book.chapters.lastIndex)
        // Read now, not from inside the coroutine below: by then the reader may already have
        // consumed the intent, and the save it guards would fire on a chapter it no longer describes.
        val landsElsewhere = hasLandingIntent(target)
        onReadingProgress() // a chapter advance is genuine reading progress
        _chapterOrdinal.value = target
        viewModelScope.launch {
            val rr = openRrFictionId
            if (rr != null) {
                // Fetch the target chapter + slide the prefetch buffer, then re-read the book so the
                // freshly-cached bodies replace their "Fetching…" placeholders. Without this refresh
                // the reader would keep the stale snapshot captured at open time and reading would
                // dead-end at the initially-buffered window.
                repository.royalRoad.advance(rr, target)
                _openBook.value = repository.royalRoad.loadBook(rr).copy(key = book.key)
            } else if (!landsElsewhere) {
                // Owned books (EPUB / PDF / AO3 snapshot) already hold every chapter inline. Skipped
                // when the reader is about to land somewhere other than the chapter's first word:
                // writing offset 0 here would be a lie for the moment before the reader saves the
                // real one, and an app killed inside that moment would reopen at the wrong place.
                book.key?.let { repository.savePosition(it.toString(), target, 0) }
            }
        }
    }

    /**
     * Turn back *into* [ordinal] from the first page of the chapter after it. Same navigation as
     * [goToChapter], except the reader opens the chapter at its **last** page: a page back is one
     * page, so crossing a chapter boundary backwards must not skip the chapter you are turning into.
     * Explicit chapter jumps (the Previous button, the contents list) still use [goToChapter] and
     * open at the beginning — that is what those mean.
     */
    fun goToChapterEnd(ordinal: Int) {
        val book = _openBook.value ?: return
        val target = ordinal.coerceIn(0, book.chapters.lastIndex)
        // A landing at the end and a restored offset are mutually exclusive; the newer intent wins.
        pendingScrollChapter = -1
        pendingScrollOffset = 0
        pendingCanonicalChapter = -1
        pendingCanonicalOffset = 0
        pendingEndChapter = target
        goToChapter(target)
    }

    /** True when the reader is arriving somewhere other than [target]'s first word. */
    private fun hasLandingIntent(target: Int): Boolean =
        pendingEndChapter == target || (pendingScrollChapter == target && pendingScrollOffset > 0)

    val isRoyalRoadOpen: Boolean get() = openRrFictionId != null

    /** Capture a highlight over the current chapter's selection and attach a note. */
    fun captureNote(selectionStart: Int, selectionEnd: Int, body: String) {
        val book = _openBook.value ?: return
        if (book.key == null) return // unkeyed book — shouldn't happen once opened via the repository
        viewModelScope.launch {
            val note = repository.captureNote(
                book = book,
                chapterOrdinal = _chapterOrdinal.value,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                noteBody = body,
                color = settings.value.highlightColor
            )
            _status.value = "Note ${note.key} captured — queued for LifeOps."
        }
    }

    /**
     * Locate [quote] in the current chapter and capture a note anchored to it. When a passage repeats
     * in one chapter, the plain first-match would anchor the wrong copy (and freeze the wrong
     * prefix/suffix); [nearOffset] — the character offset near the reader's viewport at capture — breaks
     * the tie toward the occurrence you were actually looking at.
     */
    fun captureNoteForQuote(quote: String, body: String, nearOffset: Int = 0) {
        val book = _openBook.value ?: return
        val chapter = book.chapterAt(_chapterOrdinal.value) ?: return
        val cleaned = withoutRenderedOnlyCharacters(quote)
        if (cleaned.isBlank()) {
            _status.value = "Select some text to quote."
            return
        }
        val start = nearestIndexOf(chapter.text, cleaned, nearOffset)
        if (start < 0) {
            _status.value = "Couldn’t find that passage in this chapter."
            return
        }
        captureNote(start, start + cleaned.length, body)
    }

    /**
     * Strip the characters that exist only in the drawn page.
     *
     * A selection is read back as the text the user sees, and the reader draws things the chapter's
     * canonical text does not contain: an image placeholder, a list bullet, the separators between
     * table cells the reduction ran together. Anchoring works against the canonical text, so a
     * selection that happened to span one of those would otherwise fail to match anything and the
     * note would be refused — over a passage the reader can plainly see.
     */
    private fun withoutRenderedOnlyCharacters(quote: String): String {
        var cleaned = quote.replace("\uFFFD", "")
        cleaned = cleaned.replace("   ·   ", "")
        cleaned = cleaned.removePrefix("· · ·")
        cleaned = cleaned.trimStart()
        cleaned = cleaned.removePrefix("• ")
        cleaned = Regex("^\\d+\\. ").replace(cleaned, "")
        return cleaned.trim()
    }

    /** The stored file behind an illustration reference, or null when it was not kept. */
    fun bookAsset(bookKey: String, src: String): java.io.File? = repository.bookAsset(bookKey, src)

    /**
     * Follow a contents entry. An entry that points inside a chapter (`#fragment`) lands on that
     * spot rather than at the chapter's first word — which is the whole reason a single-file book,
     * or a reference work whose spine is a hundred undifferentiated documents, gets a usable
     * contents list at all.
     */
    fun goToTocEntry(entry: TocEntry) {
        val ordinal = entry.chapterOrdinal ?: return
        val book = _openBook.value ?: return
        val offset = entry.fragment?.let { book.chapterAt(ordinal)?.anchors?.get(it) } ?: 0
        if (offset > 0) {
            pendingScrollChapter = ordinal
            pendingScrollOffset = offset
        }
        goToChapter(ordinal)
    }

    /** First index of [sub] in [text] closest to [near]; −1 if absent. */
    private fun nearestIndexOf(text: String, sub: String, near: Int): Int {
        if (sub.isEmpty()) return -1
        var from = 0
        var best = -1
        var bestDist = Long.MAX_VALUE
        while (true) {
            val i = text.indexOf(sub, from)
            if (i < 0) break
            val dist = abs(i - near).toLong()
            if (dist < bestDist) { bestDist = dist; best = i }
            from = i + 1
        }
        return best
    }

    /**
     * Capture a freestanding synthesis note over the open book, citing zero or more exact quotes
     * (each located and anchored where found). Your own artifact, kept distinct from a passage note.
     */
    fun captureSynthesis(citedQuotes: List<String>, body: String) {
        val book = _openBook.value ?: return
        if (book.key == null) return
        viewModelScope.launch {
            val note = repository.captureSynthesis(book, citedQuotes.filter { it.isNotBlank() }, body)
            _status.value = "Synthesis note ${note.key} captured (${note.references.size} citations)."
        }
    }

    /**
     * Add or edit your own words on a note — the annotation on a captured quote. Captures arrive with
     * an empty body; this is how you make something of them.
     */
    fun editNote(noteKey: String, body: String) {
        viewModelScope.launch {
            repository.editNoteBody(noteKey, body)
            _status.value = "Note updated."
        }
    }

    /**
     * Manually link a capture to a book in your library — bind ("capture") its source when no hard
     * identity ever arrived to promote it automatically. Binds the whole cluster the note belongs to, so
     * linking one capture claims all captures from the same source (e.g. a Kindle-notebook export).
     */
    fun linkNoteToBook(noteKey: String, bookKey: String) {
        viewModelScope.launch {
            val linked = repository.linkNoteToBook(noteKey, bookKey)
            _status.value = if (linked > 0) {
                "Linked $linked capture${if (linked == 1) "" else "s"} to its source."
            } else {
                "Couldn't link that note to a source."
            }
        }
    }

    /** Delete a note (its inline highlight goes with it). Local-only — see the repository. */
    fun deleteNote(noteKey: String) {
        viewModelScope.launch {
            repository.deleteNote(noteKey)
            _status.value = "Note deleted."
        }
    }

    /** Parse raw editor text into normalized tags and store them on the note (local organizational). */
    fun setNoteTags(noteKey: String, raw: String) {
        viewModelScope.launch {
            repository.setNoteTags(noteKey, Tags.parse(raw))
            _status.value = "Tags updated."
        }
    }

    /**
     * Render the currently-visible notes to Markdown and hand them to the share sheet as a `.md`
     * file. Exports the filtered set, so narrowing by tag/search first exports just that slice.
     */
    fun exportVisibleNotes(context: android.content.Context) {
        val visible = filteredNotes.value
        val tag = _activeTag.value
        val subtitle = buildString {
            append("Exported ")
            append(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()))
            append(" · ${visible.size} note${if (visible.size == 1) "" else "s"}")
            if (tag != null) append(" · #$tag")
        }
        val md = com.citation.core.note.MarkdownExport.render(visible, subtitle = subtitle)
        val ok = com.citation.app.data.NotesExporter.share(context, md)
        _status.value = if (ok) "Exporting notes…" else "Couldn't export notes."
    }

    /** Persist the reader's live position (current chapter + in-chapter scroll offset in px). */
    fun savePosition(chapterOrdinal: Int, charOffset: Int) {
        onReadingProgress() // scrolling/paging within a chapter is reading progress
        val book = _openBook.value ?: return
        val key = book.key?.toString() ?: return
        // Record how far through the book this is, so the library quotes the same number the page
        // does. Only the paged reader passes a canonical character offset here; scroll mode's
        // pixel offset would measure nothing, so it reports through the live position instead.
        val measured = _position.value
            .takeIf { it.first == chapterOrdinal }
            ?.let { ReadingProgress.at(book, it.first, it.second).fraction }
        viewModelScope.launch { repository.savePosition(key, chapterOrdinal, charOffset, measured) }
    }

    /**
     * The one-time scroll offset to restore for [chapter], or 0 if there is none for it. Cleared on
     * read so turning the page doesn't snap you back to the resumed spot.
     */
    /**
     * The one-time **canonical** offset to restore for [chapter], or -1 when there is none.
     *
     * Separate from [consumePendingScroll] because the unit is different and only the reader can
     * resolve it: the paged body turns it into a page, the scrolling body into a line's position.
     * Cleared on read, like the others.
     */
    fun consumePendingCanonical(chapter: Int): Int =
        if (chapter == pendingCanonicalChapter) {
            pendingCanonicalChapter = -1
            pendingCanonicalOffset.also { pendingCanonicalOffset = 0 }
        } else -1

    fun consumePendingScroll(chapter: Int): Int =
        if (chapter == pendingScrollChapter && pendingScrollOffset > 0) {
            pendingScrollChapter = -1
            pendingScrollOffset.also { pendingScrollOffset = 0 }
        } else 0

    /**
     * Whether [chapter] is being entered backwards and so should open at its last page (paged) or
     * scrolled to its end (scroll). Cleared on read, like [consumePendingScroll], so turning forward
     * again doesn't keep snapping to the end.
     */
    fun consumePendingEnd(chapter: Int): Boolean =
        (chapter == pendingEndChapter).also { if (it) pendingEndChapter = -1 }

    /** Resolve a note's overall degradation state for the Notes list badge. */
    suspend fun overallState(note: Note): NoteResolver.State =
        NoteResolver.overallState(repository.resolveNote(note))

    /**
     * Jump from a note back to its live context: open the source book and land on the resolved
     * chapter. Best-effort for borrowed sources — an orphaned/unavailable note just opens the book.
     */
    fun jumpToNote(note: Note) {
        val bookKey = note.source.bookKey?.toString() ?: return
        viewModelScope.launch {
            val result = repository.openBook(bookKey) ?: run {
                _status.value = "That source is no longer available — the note still holds its snapshot."
                return@launch
            }
            openRrFictionId = result.rrFictionId
            repository.markOpened(bookKey)
            _openBook.value = result.book
            startReadingSession(bookKey)
            val target = repository.resolveNote(note).firstOrNull { it.chapterOrdinal != null && it.state.canJump }
            _chapterOrdinal.value = target?.chapterOrdinal ?: 0
            if (target == null) {
                _status.value = "Passage not found in the current text — opened the book at the start."
            }
        }
    }

    /** Load the storage report for the visibility screen (per-item + aggregate, recoverability-tagged). */
    suspend fun storageReport(): com.citation.core.manifest.StorageReport = repository.storageReport()

    /** Run a sync round with LifeOps on demand (drain outbox, consume acquire intents). */
    fun sync() {
        viewModelScope.launch {
            _status.value = "Syncing with LifeOps…"
            runCatching { repository.sync() }
                .onSuccess { s ->
                    _status.value = "Synced: ${s.sent} packet(s) up" +
                        if (s.intentsCreated > 0) ", ${s.intentsCreated} book(s) added to wanted" else ""
                }
                .onFailure { _status.value = "Sync failed — will retry in the background." }
        }
    }

    fun clearStatus() { _status.value = null }

    /** Dismisses the import alert once the user has read it. */
    fun dismissImportAlert() { _importAlert.value = null }

    /**
     * Reports an import that failed on its way in from another app — a file that couldn't be read,
     * or one that turned out to be neither an EPUB nor a PDF. This can't ride the [status] line: a
     * file opened from outside lands the user in the reader, and status is only drawn on the New and
     * Settings tabs, so a failure there would be invisible. The alert is surfaced over whatever is
     * on screen instead.
     */
    fun reportImportProblem(message: String) { _importAlert.value = message }

    // --- Reading aloud ----------------------------------------------------------------------------
    //
    // The narrator is the same book, in the same position, spoken instead of set. Everything here is
    // a thin pass-through: what to say and where the voice is live in `:core` and in the narrator,
    // and duplicating any of it here is how the page and the voice would come to disagree about
    // where the reader is.

    private val idleNarration = MutableStateFlow(NarrationState())

    /** What the voice is doing, and which sentence it is on — the read-along highlight's source. */
    val narration: StateFlow<NarrationState> = narrator?.state ?: idleNarration.asStateFlow()

    /** Voice, speed and what gets spoken. */
    val speechSettings: StateFlow<SpeechSettings> =
        narrator?.speechSettings ?: MutableStateFlow(SpeechSettings()).asStateFlow()

    /** Whether this build can speak at all — false only when no narrator was supplied. */
    val canReadAloud: Boolean get() = narrator != null

    /** Whether a downloadable neural voice can be run here, as opposed to the platform's own. */
    val neuralVoicesSupported: Boolean get() = narrator?.neuralAvailable == true

    private val _installedVoices = MutableStateFlow(narrator?.installedVoices().orEmpty())

    /**
     * The voices already downloaded, for the picker.
     *
     * A flow rather than a call, because the set changes underneath the screen showing it — a
     * download finishing, a deletion — and a list that only refreshed when something else happened
     * to recompose is how a deleted voice stays on screen.
     */
    val installedVoices: StateFlow<List<InstalledVoice>> = _installedVoices.asStateFlow()

    private fun refreshInstalledVoices() {
        _installedVoices.value = narrator?.installedVoices().orEmpty()
    }

    /** The voices offered for the open book, its own language first. */
    fun voiceCatalog(): List<VoiceModel> =
        VoiceCatalog.suggestedFor(_openBook.value?.metadata?.language)

    /** Bytes the downloaded voices occupy, for the storage screen. */
    fun voiceStorageBytes(): Long = narrator?.voiceStorageBytes() ?: 0L

    /** The book the voice has open — which need not be the book on screen. */
    val nowPlayingBook: String? get() = narrator?.bookTitle

    /** Its author, for the same card. */
    val nowPlayingAuthor: String? get() = narrator?.bookAuthor

    /** The chapter the voice is in, by its own title where the source gave one. */
    fun nowPlayingChapter(ordinal: Int): String? = narrator?.chapterTitle(ordinal)

    /** Why the voice stopped, when it stopped badly. */
    val speechFailure: String? get() = narrator?.lastFailure

    private val _voiceProgress = MutableStateFlow<Pair<String, Float>?>(null)

    /** The voice being downloaded and how far along it is, or null when none is. */
    val voiceProgress: StateFlow<Pair<String, Float>?> = _voiceProgress.asStateFlow()

    /**
     * Download a voice, reporting progress and reporting the outcome on the status line.
     *
     * Runs in the ViewModel's own scope rather than a composition's, so leaving the picker part-way
     * through a sixty-megabyte download does not cancel it.
     */
    fun installVoice(model: VoiceModel) {
        val narrator = narrator ?: return
        if (_voiceProgress.value != null) return
        viewModelScope.launch {
            _voiceProgress.value = model.id to 0f
            val result = narrator.installVoice(model) { _voiceProgress.value = model.id to it }
            _voiceProgress.value = null
            refreshInstalledVoices()
            _status.value = when (result) {
                is com.citation.app.audio.VoiceStore.InstallResult.Installed -> "${model.name} is ready to read aloud."
                is com.citation.app.audio.VoiceStore.InstallResult.Failed -> result.reason
            }
        }
    }

    /** Delete a downloaded voice. */
    fun deleteVoice(model: VoiceModel) {
        if (narrator?.deleteVoice(model) == true) _status.value = "${model.name} removed."
        refreshInstalledVoices()
    }

    /**
     * Start reading the open book aloud from where the reader is.
     *
     * The voice picks up the *live* position rather than the last saved one, so pressing play after
     * scrolling starts at the sentence on screen rather than wherever the last save landed.
     */
    fun readAloud() {
        val narrator = narrator ?: return
        val book = _openBook.value ?: return
        narrator.onPosition = ::onNarratedPosition
        val (chapter, offset) = _position.value
        narrator.play(book, chapter, offset)
    }

    /** Pause the voice, keeping the book and the position. */
    fun pauseAloud() {
        narrator?.pause() ?: return
        flushListeningPosition()
    }

    /** Stop reading aloud and give the engine back. */
    fun stopAloud() {
        narrator ?: return
        // Written before the narrator forgets which book it had.
        flushListeningPosition()
        narrator.stop()
    }

    /**
     * Play or pause, for the reader's single button.
     *
     * Pausing and resuming only means anything for the book the voice actually has open. Pressing
     * play in a *different* book starts that one from the page on screen — otherwise the button
     * under one book would silently resume another, which is the sort of thing nobody debugs.
     */
    fun toggleAloud() {
        val narrator = narrator ?: return
        val onScreen = _openBook.value?.key?.toString()
        val sameBook = onScreen != null && narrator.openBookKey == onScreen
        if (narration.value.status == NarrationStatus.IDLE || !sameBook) readAloud() else narrator.toggle()
    }

    /** Move the voice by a sentence or a paragraph. */
    fun skipAloud(granularity: SkipGranularity, forward: Boolean) {
        narrator?.skip(granularity, forward)
    }

    /** Change the speaking speed; takes effect at the next sentence. */
    fun setSpeechRate(rate: Float) = configureSpeech { it.withRate(rate) }

    /** Choose a downloaded voice, or `null` to let the narrator pick the best one installed. */
    fun setVoice(voiceId: String?) = configureSpeech { it.copy(voiceId = voiceId) }

    /** Change any speech setting — the engine, the voice, what gets spoken, how it behaves. */
    fun updateSpeech(transform: (SpeechSettings) -> SpeechSettings) = configureSpeech(transform)

    /** Arm, change or cancel the sleep timer. */
    fun setSleepTimer(mode: SleepMode) = narrator?.setSleep(mode) ?: Unit

    /** Push an armed sleep timer out — the "still awake" gesture. */
    fun extendSleepTimer(millis: Long) = narrator?.extendSleep(millis) ?: Unit

    private fun configureSpeech(transform: (SpeechSettings) -> SpeechSettings) {
        val narrator = narrator ?: return
        narrator.configure(transform(narrator.speechSettings.value))
    }

    /**
     * The voice moved: follow it with the reader's own position, and save it.
     *
     * Position, yes; pace, no. [com.citation.core.reader.ReadingPace] answers "how long will this
     * take *you* to read", learnt from how fast this reader reads — and a voice at 1.5x would teach
     * it the speaking rate of an engine instead, dragging every "12 min left" in the app toward a
     * number about nobody. A reader who disagrees can turn
     * [SpeechSettings.bankListeningTowardPace] on and have listening measured like reading.
     */
    private fun onNarratedPosition(bookKey: String?, chapterOrdinal: Int, charOffset: Int) {
        // The voice keeps reading a book the reader may have closed and walked away from, so the
        // page only follows it while the two are the same book. The *saving* below always uses the
        // narrator's own key, never whatever happens to be open.
        val onScreen = bookKey != null && bookKey == _openBook.value?.key?.toString()
        var chapterChanged = false
        if (onScreen) {
            if (speechSettings.value.bankListeningTowardPace) {
                onPositionChanged(chapterOrdinal, charOffset)
                onReadingProgress()
            } else {
                _position.value = chapterOrdinal to charOffset
            }
            chapterChanged = _chapterOrdinal.value != chapterOrdinal
            _chapterOrdinal.value = chapterOrdinal
        }
        // In memory every sentence; on disk far less often. The voice moves every few seconds and
        // may do so for hours in a pocket, and a write per sentence would be a write per sentence
        // for the whole of it. Being a sentence or two stale costs a listener nothing — the worst
        // case on resume is hearing one line twice — but a crossed chapter is written at once,
        // because that is the jump somebody would notice losing.
        val now = System.currentTimeMillis()
        if (!chapterChanged && now - lastListeningSaveAt < LISTENING_SAVE_INTERVAL_MILLIS) return
        lastListeningSaveAt = now
        saveListeningPosition(bookKey, chapterOrdinal, charOffset)
    }

    /**
     * Write where the voice is right now, whatever the throttle above would have said.
     *
     * A no-op when the voice has no place — pressing pause in a book nobody has listened to must
     * not invent a listening position and have the book resume from it.
     */
    private fun flushListeningPosition() {
        val state = narration.value
        val offset = state.charOffset ?: return
        lastListeningSaveAt = System.currentTimeMillis()
        saveListeningPosition(narrator?.openBookKey, state.chapterOrdinal, offset)
    }

    private fun saveListeningPosition(bookKey: String?, chapterOrdinal: Int, charOffset: Int) {
        val key = bookKey ?: return
        // Measured against the narrator's book, which is the one these characters are counted in.
        val fraction = narrator?.progressFraction(chapterOrdinal, charOffset)
        // Written to the *listening* place, not the reading one: it is a different fact, recorded
        // while the app may be in a pocket, and it is always canonical characters — where the
        // reading position is whatever the open reading mode stores. `Resume.choose` picks between
        // them when the book is next opened.
        viewModelScope.launch { repository.saveListeningPosition(key, chapterOrdinal, charOffset, fraction) }
    }

    private var lastListeningSaveAt = 0L

    private companion object {
        /** How often the listening position reaches the database while the voice runs. */
        const val LISTENING_SAVE_INTERVAL_MILLIS = 10_000L
    }
}
