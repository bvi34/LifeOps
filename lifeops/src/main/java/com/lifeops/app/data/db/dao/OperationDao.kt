package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.OperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationDao {
    @Query("SELECT * FROM operations WHERE status = 'active' ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations ORDER BY status ASC, createdAt DESC")
    fun observeAll(): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations WHERE aspectId = :aspectId AND status = 'active' ORDER BY createdAt DESC")
    suspend fun getActiveByAspect(aspectId: String): List<OperationEntity>

    @Upsert
    suspend fun upsert(operation: OperationEntity)

    @Update
    suspend fun update(operation: OperationEntity)

    @Query("UPDATE operations SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, completedAt: String?)

    @Query("SELECT * FROM operations")
    suspend fun getAll(): List<OperationEntity>

    @Query("SELECT * FROM operations WHERE id = :id")
    suspend fun getById(id: String): OperationEntity?
}
