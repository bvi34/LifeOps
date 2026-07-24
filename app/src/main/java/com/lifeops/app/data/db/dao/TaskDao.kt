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

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<TaskEntity?>

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

    // Queued tasks are excluded so a future-dated recurring task neither gets seeded as a
    // pending duplicate nor suppresses seeding of the target week's other recurring tasks.
    @Query("SELECT * FROM tasks WHERE weekId = :weekId AND isRecurring = 1 AND status != 'queued'")
    suspend fun getRecurringByWeek(weekId: String): List<TaskEntity>

    // All recurring instances across every week. Interval-aware seeding groups these into series and
    // anchors each on its most recent instance, so an off-week (no instance in the previous week)
    // never loses the series the way a previous-week-only lookup would.
    @Query("SELECT * FROM tasks WHERE isRecurring = 1 AND status != 'queued'")
    suspend fun getAllRecurring(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'queued' ORDER BY COALESCE(dueDate, '9999') ASC, createdAt ASC")
    fun observeQueued(): Flow<List<TaskEntity>>

    @Query("UPDATE tasks SET weekId = :toWeekId WHERE weekId = :fromWeekId AND status = 'queued'")
    suspend fun moveQueuedToWeek(fromWeekId: String, toWeekId: String)

    @Query("UPDATE tasks SET status = 'pending' WHERE weekId = :weekId AND status = 'queued' AND dueDate IS NOT NULL AND dueDate <= :endDate")
    suspend fun activateQueuedDueBy(weekId: String, endDate: String)

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

    @Query("SELECT * FROM tasks WHERE carriedFromTaskId = :parentId LIMIT 1")
    suspend fun getChildOf(parentId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE weekId = :weekId AND status = 'carried_forward'")
    suspend fun getCarriedForwardByWeek(weekId: String): List<TaskEntity>

    @Query("UPDATE tasks SET status = :status, carryForwardReason = :reason WHERE id = :id")
    suspend fun updateStatusAndReason(id: String, status: String, reason: String?)

    @Query("UPDATE tasks SET status = 'pending', carryForwardReason = NULL WHERE id = :id AND status = 'carried_forward'")
    suspend fun unCarryForward(id: String)

    @Query("SELECT * FROM tasks WHERE carriedFromTaskId IN (:parentIds)")
    suspend fun getChildrenOf(parentIds: List<String>): List<TaskEntity>

    @Query("SELECT slug FROM tasks WHERE weekId = :weekId")
    suspend fun getSlugsByWeek(weekId: String): List<String>
}
