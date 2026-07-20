package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.SubtaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubtaskDao {
    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY stepOrder")
    fun observeByTask(taskId: String): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY stepOrder")
    suspend fun getByTask(taskId: String): List<SubtaskEntity>

    @Query("SELECT * FROM subtasks WHERE taskId IN (:taskIds) ORDER BY taskId, stepOrder")
    suspend fun getByTasks(taskIds: List<String>): List<SubtaskEntity>

    /** Live checked/total counts per task, for the "2/5" progress chip on task rows. */
    @Query(
        "SELECT taskId AS taskId, COUNT(*) AS total, COALESCE(SUM(isChecked), 0) AS checked " +
            "FROM subtasks WHERE taskId IN (:taskIds) GROUP BY taskId"
    )
    fun observeCountsByTasks(taskIds: List<String>): Flow<List<SubtaskCountRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subtask: SubtaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subtasks: List<SubtaskEntity>)

    @Update
    suspend fun update(subtask: SubtaskEntity)

    @Query("UPDATE subtasks SET isChecked = :checked WHERE id = :id")
    suspend fun setChecked(id: String, checked: Boolean)

    @Query("SELECT COUNT(*) FROM subtasks WHERE taskId = :taskId")
    suspend fun countByTask(taskId: String): Int

    @Query("SELECT COUNT(*) FROM subtasks WHERE taskId = :taskId AND isChecked = 1")
    suspend fun countCheckedByTask(taskId: String): Int

    @Query("DELETE FROM subtasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

/** Projection for [SubtaskDao.observeCountsByTasks]. */
data class SubtaskCountRow(
    val taskId: String,
    val total: Int,
    val checked: Int
)
