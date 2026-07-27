package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.GameUnlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameUnlockDao {
    @Query("SELECT * FROM game_unlocks ORDER BY unlockedAt")
    fun observeAll(): Flow<List<GameUnlockEntity>>

    @Query("SELECT id FROM game_unlocks")
    suspend fun getAllIds(): List<String>

    /** Idempotent — re-buying an already-owned id (shouldn't happen; store hides owned) is harmless. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GameUnlockEntity)

    @Query("DELETE FROM game_unlocks")
    suspend fun clear()
}
