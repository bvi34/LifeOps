package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lifeops.app.data.db.entities.BookEntity
import com.lifeops.app.data.db.entities.BookNoteEntity
import com.lifeops.app.data.db.entities.BookTimeEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM book_notes")
    suspend fun getAllNotes(): List<BookNoteEntity>

    @Query("SELECT * FROM book_time_entries")
    suspend fun getAllTimeEntries(): List<BookTimeEntryEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: String): Flow<BookEntity?>

    // @Upsert updates in place; @Insert(REPLACE) would delete-and-reinsert the book,
    // cascading away its notes and time entries.
    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM book_notes WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeNotes(bookId: String): Flow<List<BookNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: BookNoteEntity)

    @Query("DELETE FROM book_notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("SELECT * FROM book_time_entries WHERE bookId = :bookId ORDER BY recordedAt DESC")
    fun observeTimeEntries(bookId: String): Flow<List<BookTimeEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeEntry(entry: BookTimeEntryEntity)

    @Query("DELETE FROM book_time_entries WHERE id = :id")
    suspend fun deleteTimeEntry(id: String)
}
