package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE weekId = :weekId ORDER BY aspectId, categoryId, priority DESC")
    fun observeByWeek(weekId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE weekId = :weekId AND status = 'pending'")
    suspend fun getPendingByWeek(weekId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE weekId = :weekId")
    suspend fun getAllByWeek(weekId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE aspectId = :aspectId AND status = 'completed' AND completedAt >= :since")
    suspend fun getCompletedByAspectSince(aspectId: String, since: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE completedAt >= :since AND status = 'completed'")
    suspend fun getCompletedSince(since: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE createdAt >= :since")
    suspend fun getAllSince(since: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: String, status: String, completedAt: String)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}
