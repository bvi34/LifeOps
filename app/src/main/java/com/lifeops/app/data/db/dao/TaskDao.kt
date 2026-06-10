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

    @Query("SELECT * FROM tasks WHERE weekId = :weekId AND isRecurring = 1")
    suspend fun getRecurringByWeek(weekId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE carriedCount > 0 AND status != 'carried_forward' ORDER BY carriedCount DESC")
    suspend fun getTasksWithCarryHistory(): List<TaskEntity>

    @Query("UPDATE tasks SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    @Query("UPDATE tasks SET carriedCount = :count WHERE id = :id")
    suspend fun updateCarriedCount(id: String, count: Int)

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    // Widget: fetch only the few pending titles we render, sorted in SQLite rather than
    // loading every task into memory and sorting in Kotlin on each widget refresh.
    @Query(
        """
        SELECT title FROM tasks
        WHERE status = 'pending'
        ORDER BY
            CASE priority
                WHEN 'critical' THEN 4
                WHEN 'high' THEN 3
                WHEN 'medium' THEN 2
                ELSE 1
            END DESC,
            COALESCE(dueDate, '9999') ASC
        LIMIT :limit
        """
    )
    suspend fun getTopPendingTitles(limit: Int): List<String>

    @Query("UPDATE tasks SET status = 'pending', completedAt = NULL WHERE id = :id")
    suspend fun unmarkCompleted(id: String)

    @Query("UPDATE tasks SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun nullifyCategoryId(categoryId: String)

    @Query("UPDATE tasks SET status = 'pending' WHERE id = :id AND status = 'skipped'")
    suspend fun unSkipTask(id: String)
}
