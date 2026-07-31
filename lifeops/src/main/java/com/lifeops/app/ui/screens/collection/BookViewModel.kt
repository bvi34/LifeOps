package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Book
import com.lifeops.app.data.model.BookStatus
import com.lifeops.app.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookListUiState(
    val books: List<Book> = emptyList(),
    val showCreateDialog: Boolean = false
)

class BookViewModel(private val bookRepository: BookRepository) : ViewModel() {
    // Book lifecycle policy lives in the connection service layer (same BookService the
    // /v1/LifeOps/local/book/* routes call), so screen and connection paths behave identically.
    private val bookService = com.lifeops.app.connection.service.BookService(bookRepository)

    private val _uiState = MutableStateFlow(BookListUiState())
    val uiState: StateFlow<BookListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepository.observeAll().collectLatest { books -> _uiState.update { it.copy(books = books) } }
        }
    }

    fun showCreateDialog() = _uiState.update { it.copy(showCreateDialog = true) }
    fun hideCreateDialog() = _uiState.update { it.copy(showCreateDialog = false) }

    fun createBook(title: String, author: String?) {
        viewModelScope.launch {
            bookService.create(title, author)
            _uiState.update { it.copy(showCreateDialog = false) }
        }
    }

    fun setStatus(book: Book, status: BookStatus) {
        viewModelScope.launch { bookService.setStatus(book.id, status) }
    }
}

class BookViewModelFactory(private val bookRepository: BookRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BookViewModel(bookRepository) as T
}
