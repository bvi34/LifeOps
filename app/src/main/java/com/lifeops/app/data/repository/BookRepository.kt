package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.BookDao
import com.lifeops.app.data.model.Book
import com.lifeops.app.data.model.BookNote
import com.lifeops.app.data.model.BookStatus
import com.lifeops.app.data.model.BookTimeEntry
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class BookRepository(private val bookDao: BookDao) {
    fun observeAll(): Flow<List<Book>> = bookDao.observeAll().map { list -> list.map { it.toModel() } }
    fun observeById(id: String): Flow<Book?> = bookDao.observeById(id).map { it?.toModel() }
    fun observeNotes(bookId: String): Flow<List<BookNote>> = bookDao.observeNotes(bookId).map { list -> list.map { it.toModel() } }
    fun observeTimeEntries(bookId: String): Flow<List<BookTimeEntry>> = bookDao.observeTimeEntries(bookId).map { list -> list.map { it.toModel() } }

    // --- Reports ---
    /** All books, for range-based reading stats (finished-in-range is derived from completedAt). */
    suspend fun getAllBooks(): List<Book> = bookDao.getAll().map { it.toModel() }
    /** All reading-time entries, filtered by recordedAt in the caller. */
    suspend fun getAllTimeEntries(): List<BookTimeEntry> = bookDao.getAllTimeEntries().map { it.toModel() }

    suspend fun createBook(title: String, author: String?): Book {
        val book = Book(UUID.randomUUID().toString(), title, author, BookStatus.TO_READ, DateUtil.now())
        bookDao.upsert(book.toEntity())
        return book
    }

    suspend fun updateBook(book: Book, title: String, author: String?) {
        bookDao.upsert(book.copy(title = title, author = author).toEntity())
    }

    suspend fun setStatus(book: Book, status: BookStatus) {
        val completedAt = if (status == BookStatus.DONE) DateUtil.now() else null
        bookDao.upsert(book.copy(status = status, completedAt = completedAt).toEntity())
    }

    suspend fun deleteBook(id: String) = bookDao.delete(id)

    suspend fun addNote(bookId: String, content: String) {
        bookDao.insertNote(BookNote(UUID.randomUUID().toString(), bookId, content, DateUtil.now()).toEntity())
    }

    suspend fun deleteNote(id: String) = bookDao.deleteNote(id)

    suspend fun addTimeEntry(bookId: String, durationMinutes: Int, note: String?) {
        bookDao.insertTimeEntry(
            BookTimeEntry(UUID.randomUUID().toString(), bookId, durationMinutes, note, DateUtil.now()).toEntity()
        )
    }

    suspend fun deleteTimeEntry(id: String) = bookDao.deleteTimeEntry(id)

    /**
     * Ingest one engaged-reading telemetry record from Citation: upsert the book (keyed by Citation's
     * own [bookKey], tagged with its source + derived category) and log the engaged [minutes] as a
     * time entry stamped at the session's [occurredAt]. The logged minutes then feed reading rewards
     * at week-close (see [TaskRepository.closeWeek]). Idempotent on the book row; each call adds one
     * time entry (the caller de-dupes packets via the sync cursor). No-op for zero minutes.
     */
    suspend fun ingestReadingTelemetry(
        bookKey: String,
        title: String,
        sourceType: String,
        minutes: Int,
        occurredAt: Long
    ) {
        if (minutes <= 0) return
        val category = com.lifeops.app.util.ReadingRewards.defaultCategory(sourceType).name
        val existing = bookDao.getById(bookKey)?.toModel()
        val book = existing?.copy(title = title, sourceType = sourceType, category = category)
            ?: Book(
                id = bookKey,
                title = title,
                status = BookStatus.READING,
                createdAt = DateUtil.now(),
                sourceType = sourceType,
                category = category
            )
        bookDao.upsert(book.toEntity())
        bookDao.insertTimeEntry(
            BookTimeEntry(
                id = UUID.randomUUID().toString(),
                bookId = bookKey,
                durationMinutes = minutes,
                note = "Citation",
                recordedAt = DateUtil.isoFromEpoch(occurredAt)
            ).toEntity()
        )
    }
}
