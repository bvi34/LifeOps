package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.CostResourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CostResourceDao {
    @Query("SELECT * FROM cost_resources WHERE isActive = 1 ORDER BY sortIndex, name")
    fun observeActive(): Flow<List<CostResourceEntity>>

    @Query("SELECT * FROM cost_resources ORDER BY sortIndex, name")
    fun observeAll(): Flow<List<CostResourceEntity>>

    @Query("SELECT * FROM cost_resources ORDER BY sortIndex, name")
    suspend fun getAllSync(): List<CostResourceEntity>

    @Upsert
    suspend fun upsert(resource: CostResourceEntity)

    @Query("UPDATE cost_resources SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)
}
