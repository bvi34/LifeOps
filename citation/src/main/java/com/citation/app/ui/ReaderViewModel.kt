package com.citation.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.citation.app.data.CitationRepository
import com.citation.core.model.Book
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

    private val _openBook = MutableStateFlow<Book?>(null)
    val openBook: StateFlow<Book?> = _openBook.asStateFlow()

    private val _chapterOrdinal = MutableStateFlow(0)
    val chapterOrdinal: StateFlow<Int> = _chapterOrdinal.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

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
            _openBook.value = repository.loadBook(bookKey)
            _chapterOrdinal.value = 0
        }
    }

    fun closeBook() {
        _openBook.value = null
    }

    fun goToChapter(ordinal: Int) {
        val book = _openBook.value ?: return
        _chapterOrdinal.value = ordinal.coerceIn(0, book.chapters.lastIndex)
        viewModelScope.launch {
            book.key?.let { repository.savePosition(it.toString(), _chapterOrdinal.value, 0) }
        }
    }

    /** Capture a highlight over the current chapter's selection and attach a note. */
    fun captureNote(selectionStart: Int, selectionEnd: Int, body: String) {
        val book = _openBook.value ?: return
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

    fun clearStatus() { _status.value = null }
}
