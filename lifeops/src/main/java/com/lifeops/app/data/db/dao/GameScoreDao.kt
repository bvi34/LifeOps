package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lifeops.app.data.db.entities.GameScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameScoreDao {
    /** Best runs first, then most recent — the scoreboard's default ordering. */
    @Query("SELECT * FROM game_scores ORDER BY score DESC, createdAt DESC")
    fun observeAll(): Flow<List<GameScoreEntity>>

    @Insert
    suspend fun insert(entity: GameScoreEntity)

    @Query("SELECT MAX(score) FROM game_scores")
    suspend fun bestScore(): Long?

    @Query("DELETE FROM game_scores")
    suspend fun clear()
}
