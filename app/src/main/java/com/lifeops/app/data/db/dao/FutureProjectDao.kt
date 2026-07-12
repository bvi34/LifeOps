package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.FutureProjectEntity
import com.lifeops.app.data.db.entities.FutureProjectNoteEntity
import kotlinx.coroutines.flow.Flow

data class FutureProjectWithPreview(
    @Embedded val project: FutureProjectEntity,
    val latestNote: String?
)

@Dao
interface FutureProjectDao {
    @Query("""
        SELECT fp.*, (
            SELECT n.content FROM future_project_notes n
            WHERE n.projectId = fp.id ORDER BY n.createdAt DESC LIMIT 1
        ) AS latestNote
        FROM future_projects fp ORDER BY fp.updatedAt DESC
    """)
    fun observeAllWithPreview(): Flow<List<FutureProjectWithPreview>>

    @Query("SELECT * FROM future_projects")
    suspend fun getAll(): List<FutureProjectEntity>

    @Query("SELECT * FROM future_projects WHERE id = :id")
    fun observeById(id: String): Flow<FutureProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: FutureProjectEntity)

    @Query("UPDATE future_projects SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: String)

    @Query("DELETE FROM future_projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM future_project_notes WHERE projectId = :projectId ORDER BY createdAt ASC")
    fun observeNotes(projectId: String): Flow<List<FutureProjectNoteEntity>>

    @Query("SELECT * FROM future_project_notes")
    suspend fun getAllNotes(): List<FutureProjectNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: FutureProjectNoteEntity)
}
