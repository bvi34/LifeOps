package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.TaskNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskNoteDao {
    @Query("SELECT tn.* FROM task_notes tn INNER JOIN tasks t ON t.id = tn.taskId WHERE t.weekId = :weekId ORDER BY tn.createdAt ASC")
    fun observeByWeek(weekId: String): Flow<List<TaskNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: TaskNoteEntity)

    @Query("DELETE FROM task_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM task_notes")
    suspend fun getAll(): List<TaskNoteEntity>
}
