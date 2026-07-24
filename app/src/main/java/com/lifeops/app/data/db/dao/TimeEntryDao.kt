package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.TimeEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeEntryDao {
    @Query("SELECT te.* FROM time_entries te INNER JOIN tasks t ON t.id = te.taskId WHERE t.weekId = :weekId ORDER BY te.recordedAt DESC")
    fun observeByWeek(weekId: String): Flow<List<TimeEntryEntity>>

    @Query("SELECT te.* FROM time_entries te INNER JOIN tasks t ON t.id = te.taskId WHERE t.weekId = :weekId")
    suspend fun getByWeek(weekId: String): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE recordedAt >= :since")
    suspend fun getAllSince(since: String): List<TimeEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TimeEntryEntity)

    @Query("SELECT * FROM time_entries")
    suspend fun getAll(): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE taskId = :taskId")
    suspend fun getByTask(taskId: String): List<TimeEntryEntity>

    @Query("SELECT * FROM time_entries WHERE taskId = :taskId")
    fun observeByTask(taskId: String): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE taskId IN (:taskIds)")
    suspend fun getByTaskIds(taskIds: List<String>): List<TimeEntryEntity>

    @Query("UPDATE time_entries SET taskId = :newTaskId WHERE taskId = :oldTaskId")
    suspend fun reassignToTask(oldTaskId: String, newTaskId: String)
}
