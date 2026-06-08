package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.ResourceTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResourceTransactionDao {
    @Query("SELECT * FROM resource_transactions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ResourceTransactionEntity>>

    @Query("SELECT * FROM resource_transactions WHERE resourceId = :resourceId ORDER BY createdAt DESC LIMIT 10")
    fun observeByResource(resourceId: String): Flow<List<ResourceTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: ResourceTransactionEntity)
}
