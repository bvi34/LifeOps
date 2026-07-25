package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.data.CitationRepository
import com.citation.core.model.Book
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _openBook = MutableStateFlow<Book?>(null)
    val openBook: StateFlow<Book?> = _openBook.asStateFlow()

    private val _chapterOrdinal = MutableStateFlow(0)
    val chapterOrdinal: StateFlow<Int> = _chapterOrdinal.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // Non-null while a Royal Road serial is open, so page turns can slide its prefetch buffer.
    private var openRrFictionId: Long? = null

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
            val result = repository.openBook(bookKey)
            openRrFictionId = result?.rrFictionId
            _openBook.value = result?.book
            _chapterOrdinal.value = 0
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

    fun goToChapter(ordinal: Int) {
        val book = _openBook.value ?: return
        val target = ordinal.coerceIn(0, book.chapters.lastIndex)
        _chapterOrdinal.value = target
        viewModelScope.launch {
            val rr = openRrFictionId
            if (rr != null) {
                repository.royalRoad.advance(rr, target) // slide the prefetch buffer forward
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
     * Convenience for the UI: locate [quote] in the current chapter and capture a note anchored to
     * it. Real text-selection would supply offsets directly; this keeps the skeleton UI usable
     * without a full selection toolbar.
     */
    fun captureNoteForQuote(quote: String, body: String) {
        val book = _openBook.value ?: return
        val chapter = book.chapterAt(_chapterOrdinal.value) ?: return
        val start = chapter.text.indexOf(quote)
        if (start < 0) {
            _status.value = "Couldn’t find that passage in this chapter."
            return
        }
        captureNote(start, start + quote.length, body)
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
            _openBook.value = result.book
            val target = repository.resolveNote(note).firstOrNull { it.chapterOrdinal != null && it.state.canJump }
            _chapterOrdinal.value = target?.chapterOrdinal ?: 0
            if (target == null) {
                _status.value = "Passage not found in the current text — opened the book at the start."
            }
        }
    }

    fun clearStatus() { _status.value = null }
}
