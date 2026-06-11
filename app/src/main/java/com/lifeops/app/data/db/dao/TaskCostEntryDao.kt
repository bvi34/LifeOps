package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.TaskCostEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskCostEntryDao {
    @Query("SELECT tce.* FROM task_cost_entries tce INNER JOIN tasks t ON t.id = tce.taskId WHERE t.weekId = :weekId ORDER BY tce.recordedAt DESC")
    fun observeByWeek(weekId: String): Flow<List<TaskCostEntryEntity>>

    @Query("SELECT * FROM task_cost_entries WHERE taskId = :taskId ORDER BY recordedAt DESC")
    fun observeByTask(taskId: String): Flow<List<TaskCostEntryEntity>>

    @Query("SELECT * FROM task_cost_entries WHERE recordedAt >= :since ORDER BY recordedAt DESC")
    suspend fun getSince(since: String): List<TaskCostEntryEntity>

    @Query("SELECT tce.* FROM task_cost_entries tce INNER JOIN tasks t ON t.id = tce.taskId WHERE t.weekId = :weekId")
    suspend fun getByWeek(weekId: String): List<TaskCostEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TaskCostEntryEntity)

    @Query("DELETE FROM task_cost_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM task_cost_entries")
    suspend fun getAll(): List<TaskCostEntryEntity>

    @Query("SELECT * FROM task_cost_entries WHERE taskId = :taskId ORDER BY recordedAt DESC")
    suspend fun getByTask(taskId: String): List<TaskCostEntryEntity>

    @Query("UPDATE task_cost_entries SET taskId = :newTaskId WHERE taskId = :oldTaskId")
    suspend fun reassignToTask(oldTaskId: String, newTaskId: String)
}
