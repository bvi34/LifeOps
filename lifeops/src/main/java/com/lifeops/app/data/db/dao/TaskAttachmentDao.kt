package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.TaskAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskAttachmentDao {
    @Query("SELECT * FROM task_attachments WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeByTask(taskId: String): Flow<List<TaskAttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: TaskAttachmentEntity)

    @Query("DELETE FROM task_attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM task_attachments")
    suspend fun getAll(): List<TaskAttachmentEntity>
}
