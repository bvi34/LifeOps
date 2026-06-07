package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.GameResourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameResourceDao {
    @Query("SELECT * FROM game_resources ORDER BY slotIndex")
    fun observeAll(): Flow<List<GameResourceEntity>>

    @Query("SELECT * FROM game_resources WHERE id = :id")
    suspend fun getById(id: String): GameResourceEntity?

    @Query("SELECT * FROM game_resources ORDER BY slotIndex")
    suspend fun getAll(): List<GameResourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(resource: GameResourceEntity)

    @Update
    suspend fun update(resource: GameResourceEntity)

    @Query("UPDATE game_resources SET currentValue = currentValue + :amount, lifetimeEarned = lifetimeEarned + :amount WHERE id = :id")
    suspend fun addValue(id: String, amount: Int)
}
