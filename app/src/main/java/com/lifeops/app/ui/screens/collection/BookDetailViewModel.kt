package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Book
import com.lifeops.app.data.model.BookNote
import com.lifeops.app.data.model.BookStatus
import com.lifeops.app.data.model.BookTimeEntry
import com.lifeops.app.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: Book? = null,
    val notes: List<BookNote> = emptyList(),
    val timeEntries: List<BookTimeEntry> = emptyList(),
    val totalMinutes: Int = 0,
    val showEditDialog: Boolean = false,
    val showAddNoteDialog: Boolean = false,
    val showAddTimeDialog: Boolean = false
)

class BookDetailViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                bookRepository.observeById(bookId),
                bookRepository.observeNotes(bookId),
                bookRepository.observeTimeEntries(bookId)
            ) { book, notes, timeEntries -> Triple(book, notes, timeEntries) }
                .collectLatest { (book, notes, timeEntries) ->
                    _uiState.update {
                        it.copy(
                            book = book,
                            notes = notes,
                            timeEntries = timeEntries,
                            totalMinutes = timeEntries.sumOf { entry -> entry.durationMinutes }
                        )
                    }
                }
        }
    }

    fun showEditDialog() = _uiState.update { it.copy(showEditDialog = true) }
    fun hideEditDialog() = _uiState.update { it.copy(showEditDialog = false) }

    fun update(title: String, author: String?) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateBook(book, title, author)
            _uiState.update { it.copy(showEditDialog = false) }
        }
    }

    fun setStatus(status: BookStatus) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch { bookRepository.setStatus(book, status) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
            onDeleted()
        }
    }

    fun showAddNoteDialog() = _uiState.update { it.copy(showAddNoteDialog = true) }
    fun hideAddNoteDialog() = _uiState.update { it.copy(showAddNoteDialog = false) }

    fun addNote(content: String) {
        viewModelScope.launch {
            bookRepository.addNote(bookId, content)
            _uiState.update { it.copy(showAddNoteDialog = false) }
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch { bookRepository.deleteNote(id) }
    }

    fun showAddTimeDialog() = _uiState.update { it.copy(showAddTimeDialog = true) }
    fun hideAddTimeDialog() = _uiState.update { it.copy(showAddTimeDialog = false) }

    fun addTimeEntry(minutes: Int, note: String?) {
        viewModelScope.launch {
            bookRepository.addTimeEntry(bookId, minutes, note)
            _uiState.update { it.copy(showAddTimeDialog = false) }
        }
    }

    fun deleteTimeEntry(id: String) {
        viewModelScope.launch { bookRepository.deleteTimeEntry(id) }
    }
}

class BookDetailViewModelFactory(
    private val bookId: String,
    private val bookRepository: BookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BookDetailViewModel(bookId, bookRepository) as T
}
