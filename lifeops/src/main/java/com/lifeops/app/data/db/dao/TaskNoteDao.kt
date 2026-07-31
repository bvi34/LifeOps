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

    @Query("SELECT * FROM task_notes WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun getByTask(taskId: String): List<TaskNoteEntity>

    @Query("SELECT * FROM task_notes WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeByTask(taskId: String): Flow<List<TaskNoteEntity>>

    @Query("SELECT * FROM task_notes WHERE taskId IN (:taskIds) ORDER BY createdAt ASC")
    suspend fun getByTaskIds(taskIds: List<String>): List<TaskNoteEntity>

    @Query("UPDATE task_notes SET taskId = :newTaskId WHERE taskId = :oldTaskId")
    suspend fun reassignToTask(oldTaskId: String, newTaskId: String)
}
