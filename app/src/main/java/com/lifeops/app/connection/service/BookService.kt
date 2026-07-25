package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Book
import com.lifeops.app.data.model.BookStatus
import com.lifeops.app.data.repository.BookRepository

/** Use-case layer for the reading list: books, their notes, and reading-time entries. */
class BookService(private val bookRepository: BookRepository) {

    suspend fun create(title: String, author: String? = null): Book {
        require(title.isNotBlank()) { "Book title must not be blank" }
        return bookRepository.createBook(title.trim(), author?.takeIf { it.isNotBlank() })
    }

    /** Rename / re-attribute a book. Null if [id] is unknown. */
    suspend fun update(id: String, title: String? = null, author: String? = null): Book? {
        val current = load(id) ?: return null
        val newTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title
        val newAuthor = if (author != null) author.takeIf { it.isNotBlank() } else current.author
        bookRepository.updateBook(current, newTitle, newAuthor)
        return current.copy(title = newTitle, author = newAuthor)
    }

    /** Set reading status (stamps completedAt on DONE). False if [id] is unknown. */
    suspend fun setStatus(id: String, status: BookStatus): Boolean {
        val book = load(id) ?: return false
        bookRepository.setStatus(book, status)
        return true
    }

    suspend fun delete(id: String): Boolean {
        load(id) ?: return false
        bookRepository.deleteBook(id)
        return true
    }

    suspend fun addNote(bookId: String, content: String): Boolean {
        if (content.isBlank()) return false
        load(bookId) ?: return false
        bookRepository.addNote(bookId, content.trim())
        return true
    }

    suspend fun logTime(bookId: String, durationMinutes: Int, note: String? = null): Boolean {
        if (durationMinutes <= 0) return false
        load(bookId) ?: return false
        bookRepository.addTimeEntry(bookId, durationMinutes, note)
        return true
    }

    // BookRepository exposes no single-id getter; the reading list is small, so resolve from the
    // full set. If that ever grows, add a suspend getById to the repository instead.
    private suspend fun load(id: String): Book? = bookRepository.getAllBooks().firstOrNull { it.id == id }
}
