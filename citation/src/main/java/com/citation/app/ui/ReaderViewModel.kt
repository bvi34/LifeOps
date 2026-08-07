package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.data.CitationRepository
import com.citation.app.data.OreillyAccess
import com.citation.core.capture.CaptureClusterer
import com.citation.core.capture.CaptureTriage
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.NoteSearch
import com.citation.core.reader.ReadingMeter
import com.citation.core.note.NoteType
import com.citation.core.note.TagCount
import com.citation.core.note.Tags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
class ReaderViewModel(private val repository: CitationRepository) : ViewModel() {

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

    private val _openBook = MutableStateFlow<Book?>(null)
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

    private val _chapterOrdinal = MutableStateFlow(0)
    val chapterOrdinal: StateFlow<Int> = _chapterOrdinal.asStateFlow()

    // Scroll position to restore on the first paint of a reopened book: the chapter it belongs to and
    // the pixel offset within it. Consumed once by the reader, so a page turn doesn't re-apply it.
    private var pendingScrollChapter = -1
    private var pendingScrollOffset = 0

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

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

    /** Reader went to the background (lifecycle stop): bank + report engaged time, keep the session. */
    fun onReaderHidden() = reportReading()

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
        if (minutes > 0) viewModelScope.launch { repository.recordReadingTelemetry(key, minutes) }
    }

    // Non-null while a Royal Road serial is open, so page turns can slide its prefetch buffer.
    private var openRrFictionId: Long? = null
    private var openAo3WorkId: Long? = null

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

    fun importEpub(bytes: ByteArray) {
        viewModelScope.launch {
            val book = repository.importEpub(bytes)
            _status.value = if (book != null) {
                "Imported “${book.metadata.title}” (${book.chapters.size} chapters)"
            } else {
                "That file didn’t parse as an EPUB."
            }
        }
    }

    fun open(bookKey: String) {
        viewModelScope.launch {
            // Read cache staleness from the *prior* open time, before markOpened restamps it to now.
            val warmCacheStale = repository.oreillyWarmCacheStale(bookKey)
            repository.markOpened(bookKey) // stamp for the Read tab's resume
            when (repository.sourceTypeOf(bookKey)) {
                SourceType.PDF -> _pdfSession.value = repository.pdfSession(bookKey)
                SourceType.OREILLY ->
                    _oreillySession.value = repository.oreillySession(bookKey)?.copy(purgeWarmCache = warmCacheStale)
                SourceType.KINDLE -> {
                    _kindleSession.value = repository.kindleSession(bookKey)
                    _kindleSession.value?.bookKey?.let { repository.beginReaderContext(it) }
                }
                else -> {
                    val result = repository.openBook(bookKey)
                    openRrFictionId = result?.rrFictionId
                    openAo3WorkId = result?.ao3WorkId
                    _openBook.value = result?.book
                    // Land where you left off: restore the saved chapter, and stage the scroll offset
                    // for the reader to apply on first paint. A borrowed serial (Royal Road / AO3)
                    // needs its buffer slid to the resumed chapter, so route through goToChapter.
                    val lastIndex = (result?.book?.chapters?.lastIndex ?: 0).coerceAtLeast(0)
                    val savedChapter = (result?.chapterOrdinal ?: 0).coerceIn(0, lastIndex)
                    pendingScrollChapter = savedChapter
                    pendingScrollOffset = result?.charOffset ?: 0
                    _chapterOrdinal.value = savedChapter
                    val isSerial = result?.rrFictionId != null || result?.ao3WorkId != null
                    if (isSerial && savedChapter > 0) goToChapter(savedChapter)
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
        }
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
                    openAo3WorkId = null
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
     * Open an Archive of Our Own work *through the reader* — the WebView is only for skimming/catalog,
     * so even chapter 1 comes back as internal-model text here. The AO3 twin of [openRoyalRoad]; the
     * work is registered as a sovereign book, so notes on it work like any other source.
     */
    fun openAo3(workId: Long) {
        viewModelScope.launch {
            _status.value = "Fetching Archive of Our Own catalog…"
            runCatching { repository.openAo3(workId) }
                .onSuccess { book ->
                    openAo3WorkId = workId
                    openRrFictionId = null
                    book.key?.let { repository.markOpened(it.toString()) }
                    _openBook.value = book
                    _chapterOrdinal.value = 0
                    _status.value = "Opened “${book.metadata.title}”."
                    startReadingSession(book.key?.toString())
                }
                .onFailure { _status.value = "Couldn’t open that Archive of Our Own work." }
        }
    }

    fun favoriteAo3() {
        val workId = openAo3WorkId ?: return
        viewModelScope.launch {
            repository.ao3.markFavorite(workId, true)
            _status.value = "Favourited — full backfill queued, kept indefinitely."
        }
    }

    fun closeBook() {
        endReadingSession()
        openRrFictionId = null
        openAo3WorkId = null
        _openBook.value = null
    }

    /**
     * Remove a book from the library. For a Royal Road serial this also un-favourites it and drops its
     * cached chapter bodies (the "uncache" the user wants); if it happens to be the one open in the
     * reader, the reader is closed too. Notes on it are kept (they hold their own frozen snapshots).
     */
    fun deleteBook(book: CitationRepository.BookSummary) {
        viewModelScope.launch {
            repository.deleteBook(book.key)
            if (_openBook.value?.key?.toString() == book.key) closeBook()
            _status.value = "Removed “${book.title}”."
        }
    }

    fun goToChapter(ordinal: Int) {
        val book = _openBook.value ?: return
        val target = ordinal.coerceIn(0, book.chapters.lastIndex)
        onReadingProgress() // a chapter advance is genuine reading progress
        _chapterOrdinal.value = target
        viewModelScope.launch {
            val rr = openRrFictionId
            val ao3 = openAo3WorkId
            when {
                // Fetch the target chapter + slide the prefetch buffer, then re-read the book so the
                // freshly-cached bodies replace their "Fetching…" placeholders. Without this refresh
                // the reader would keep the stale snapshot captured at open time and reading would
                // dead-end at the initially-buffered window.
                rr != null -> {
                    repository.royalRoad.advance(rr, target)
                    _openBook.value = repository.royalRoad.loadBook(rr).copy(key = book.key)
                }
                ao3 != null -> {
                    repository.ao3.advance(ao3, target)
                    _openBook.value = repository.ao3.loadBook(ao3).copy(key = book.key)
                }
                else -> book.key?.let { repository.savePosition(it.toString(), target, 0) }
            }
        }
    }

    val isRoyalRoadOpen: Boolean get() = openRrFictionId != null
    val isAo3Open: Boolean get() = openAo3WorkId != null

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
                noteBody = body
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
        val start = nearestIndexOf(chapter.text, quote, nearOffset)
        if (start < 0) {
            _status.value = "Couldn’t find that passage in this chapter."
            return
        }
        captureNote(start, start + quote.length, body)
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
        val key = _openBook.value?.key?.toString() ?: return
        viewModelScope.launch { repository.savePosition(key, chapterOrdinal, charOffset) }
    }

    /**
     * The one-time scroll offset to restore for [chapter], or 0 if there is none for it. Cleared on
     * read so turning the page doesn't snap you back to the resumed spot.
     */
    fun consumePendingScroll(chapter: Int): Int =
        if (chapter == pendingScrollChapter && pendingScrollOffset > 0) {
            pendingScrollChapter = -1
            pendingScrollOffset.also { pendingScrollOffset = 0 }
        } else 0

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
            openAo3WorkId = result.ao3WorkId
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
}
