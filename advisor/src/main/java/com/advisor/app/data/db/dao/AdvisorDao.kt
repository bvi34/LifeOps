package com.advisor.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.advisor.app.data.db.entities.AdvisorMessageEntity
import com.advisor.app.data.db.entities.AppPermissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AdvisorDao {

    // --- permissions ---

    @Query("SELECT * FROM advisor_permissions")
    suspend fun getPermissions(): List<AppPermissionEntity>

    @Query("SELECT * FROM advisor_permissions")
    fun observePermissions(): Flow<List<AppPermissionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPermission(permission: AppPermissionEntity)

    // --- conversation ---

    @Query("SELECT * FROM advisor_messages ORDER BY createdAt ASC")
    fun observeMessages(): Flow<List<AdvisorMessageEntity>>

    @Query("SELECT * FROM advisor_messages WHERE role = :role ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastMessageOf(role: String): AdvisorMessageEntity?

    /** The most recent [limit] messages, newest first — reverse for chronological conversation context. */
    @Query("SELECT * FROM advisor_messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentMessages(limit: Int): List<AdvisorMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMessage(message: AdvisorMessageEntity)

    @Query("DELETE FROM advisor_messages")
    suspend fun clearMessages()
}
