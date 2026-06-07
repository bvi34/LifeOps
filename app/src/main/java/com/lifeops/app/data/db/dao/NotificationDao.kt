package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.NotificationEntity

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE taskId = :taskId")
    suspend fun getByTask(taskId: String): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE isSent = 0 ORDER BY scheduledAt")
    suspend fun getPending(): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<NotificationEntity>)

    @Query("UPDATE notifications SET isSent = 1 WHERE id = :id")
    suspend fun markSent(id: String)

    @Query("DELETE FROM notifications WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}
