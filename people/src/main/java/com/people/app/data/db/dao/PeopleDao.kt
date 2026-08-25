package com.people.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.people.app.data.db.entities.ImportantDateEntity
import com.people.app.data.db.entities.PersonEntity
import com.people.app.data.db.entities.PersonNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeopleDao {

    // --- people ---
    @Query("SELECT * FROM people WHERE archived = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeActive(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people ORDER BY archived, sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE id = :id")
    fun observeById(id: String): Flow<PersonEntity?>

    @Query("SELECT * FROM people ORDER BY archived, sortOrder, name COLLATE NOCASE")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun getById(id: String): PersonEntity?

    @Query("SELECT * FROM people WHERE personKey = :personKey LIMIT 1")
    suspend fun getByKey(personKey: String): PersonEntity?

    /**
     * The outbound queue, derived rather than stored: everything edited since the peer last
     * acknowledged. A durable outbox would be a second copy of this same fact, free to disagree
     * with it after a crash.
     */
    @Query("SELECT * FROM people WHERE syncVersion > :sinceVersion ORDER BY syncVersion")
    suspend fun changedSince(sinceVersion: Long): List<PersonEntity>

    @Query("SELECT COALESCE(MAX(syncVersion), 0) FROM people")
    suspend fun maxSyncVersion(): Long

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM people")
    suspend fun nextSortOrder(): Int

    @Upsert
    suspend fun upsert(person: PersonEntity)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun delete(id: String)

    // --- notes ---
    @Query("SELECT * FROM person_notes WHERE personId = :personId ORDER BY createdAt DESC")
    fun observeNotes(personId: String): Flow<List<PersonNoteEntity>>

    @Query("SELECT * FROM person_notes ORDER BY createdAt DESC")
    suspend fun getAllNotes(): List<PersonNoteEntity>

    @Upsert
    suspend fun upsertNote(note: PersonNoteEntity)

    @Query("DELETE FROM person_notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    // --- important dates ---
    @Query("SELECT * FROM important_dates WHERE personId = :personId ORDER BY monthDay")
    fun observeDates(personId: String): Flow<List<ImportantDateEntity>>

    @Query("SELECT * FROM important_dates ORDER BY monthDay")
    fun observeAllDates(): Flow<List<ImportantDateEntity>>

    @Query("SELECT * FROM important_dates ORDER BY monthDay")
    suspend fun getAllDates(): List<ImportantDateEntity>

    @Upsert
    suspend fun upsertDate(date: ImportantDateEntity)

    @Query("DELETE FROM important_dates WHERE id = :id")
    suspend fun deleteDate(id: String)
}
