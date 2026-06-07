package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.GameResourceMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameResourceMappingDao {
    @Query("SELECT * FROM game_resource_mappings")
    fun observeAll(): Flow<List<GameResourceMappingEntity>>

    @Query("SELECT * FROM game_resource_mappings WHERE gameResourceId = :gameResourceId")
    fun observeByGameResource(gameResourceId: String): Flow<List<GameResourceMappingEntity>>

    @Query("SELECT * FROM game_resource_mappings WHERE aspectId = :aspectId")
    suspend fun getByAspect(aspectId: String): List<GameResourceMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: GameResourceMappingEntity)

    @Delete
    suspend fun delete(mapping: GameResourceMappingEntity)

    @Query("DELETE FROM game_resource_mappings WHERE gameResourceId = :gameResourceId AND aspectId = :aspectId")
    suspend fun deleteByResourceAndAspect(gameResourceId: String, aspectId: String)

    @Query("DELETE FROM game_resource_mappings WHERE gameResourceId = :gameResourceId")
    suspend fun deleteByGameResource(gameResourceId: String)
}
