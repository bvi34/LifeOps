package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lifeops.app.data.db.entities.FutureOperationEntity
import com.lifeops.app.data.db.entities.FutureOperationNoteEntity
import kotlinx.coroutines.flow.Flow

data class FutureOperationWithPreview(
    @Embedded val operation: FutureOperationEntity,
    val latestNote: String?
)

@Dao
interface FutureOperationDao {
    @Query("""
        SELECT fp.*, (
            SELECT n.content FROM future_operation_notes n
            WHERE n.operationId = fp.id ORDER BY n.createdAt DESC LIMIT 1
        ) AS latestNote
        FROM future_operations fp ORDER BY fp.updatedAt DESC
    """)
    fun observeAllWithPreview(): Flow<List<FutureOperationWithPreview>>

    @Query("SELECT * FROM future_operations")
    suspend fun getAll(): List<FutureOperationEntity>

    @Query("SELECT * FROM future_operations WHERE id = :id")
    fun observeById(id: String): Flow<FutureOperationEntity?>

    // @Upsert updates in place on conflict. @Insert(REPLACE) must never be used for parent
    // rows here: REPLACE deletes the old row before re-inserting, which cascades and wipes
    // the operation's notes.
    @Upsert
    suspend fun upsert(operation: FutureOperationEntity)

    @Query("UPDATE future_operations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: String)

    @Query("UPDATE future_operations SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM future_operations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM future_operation_notes WHERE operationId = :operationId ORDER BY createdAt ASC")
    fun observeNotes(operationId: String): Flow<List<FutureOperationNoteEntity>>

    @Query("SELECT * FROM future_operation_notes WHERE operationId = :operationId ORDER BY createdAt ASC")
    suspend fun getNotes(operationId: String): List<FutureOperationNoteEntity>

    @Query("SELECT * FROM future_operation_notes")
    suspend fun getAllNotes(): List<FutureOperationNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: FutureOperationNoteEntity)
}
