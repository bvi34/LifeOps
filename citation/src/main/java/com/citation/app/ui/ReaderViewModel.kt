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
import com.citation.core.note.NoteType
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

    // Non-null while a Royal Road serial is open, so page turns can slide its prefetch buffer.
    private var openRrFictionId: Long? = null

    // The PDF paged reader and the O'Reilly read-in-place reader are separate tracks from the
    // flowing reader; when one is set the UI shows that track instead.
    private val _pdfSession = MutableStateFlow<CitationRepository.PdfSession?>(null)
    val pdfSession: StateFlow<CitationRepository.PdfSession?> = _pdfSession.asStateFlow()

    private val _oreillySession = MutableStateFlow<CitationRepository.OreillySession?>(null)
    val oreillySession: StateFlow<CitationRepository.OreillySession?> = _oreillySession.asStateFlow()

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
                else -> {
                    val result = repository.openBook(bookKey)
                    openRrFictionId = result?.rrFictionId
                    _openBook.value = result?.book
                    // Land where you left off: restore the saved chapter, and stage the scroll offset
                    // for the reader to apply on first paint. Royal Road needs its buffer slid to the
                    // resumed chapter, so route through goToChapter for that side.
                    val lastIndex = (result?.book?.chapters?.lastIndex ?: 0).coerceAtLeast(0)
                    val savedChapter = (result?.chapterOrdinal ?: 0).coerceIn(0, lastIndex)
                    pendingScrollChapter = savedChapter
                    pendingScrollOffset = result?.charOffset ?: 0
                    _chapterOrdinal.value = savedChapter
                    if (result?.rrFictionId != null && savedChapter > 0) goToChapter(savedChapter)
                }
            }
        }
    }

    fun importPdf(bytes: ByteArray, title: String) {
        viewModelScope.launch {
            val key = repository.importPdf(bytes, title)
            repository.markOpened(key)
            _status.value = "Imported PDF “$title”."
            _pdfSession.value = repository.pdfSession(key)
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

    fun closePdf() { _pdfSession.value = null }

    fun closeOreilly() { _oreillySession.value = null }

    /** Persist the O'Reilly reader's position so the next open lands one tap from your spot. */
    fun saveOreillyPosition(location: String) {
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

    fun closeBook() {
        openRrFictionId = null
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
            } else {
                book.key?.let { repository.savePosition(it.toString(), target, 0) }
            }
        }
    }

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

    /** Delete a note (its inline highlight goes with it). Local-only — see the repository. */
    fun deleteNote(noteKey: String) {
        viewModelScope.launch {
            repository.deleteNote(noteKey)
            _status.value = "Note deleted."
        }
    }

    /** Persist the reader's live position (current chapter + in-chapter scroll offset in px). */
    fun savePosition(chapterOrdinal: Int, charOffset: Int) {
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
            repository.markOpened(bookKey)
            _openBook.value = result.book
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
