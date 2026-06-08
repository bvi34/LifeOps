package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.WeekEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeekDao {
    @Query("SELECT * FROM weeks WHERE isClosed = 0 ORDER BY startDate DESC LIMIT 1")
    fun observeCurrentWeek(): Flow<WeekEntity?>

    @Query("SELECT * FROM weeks ORDER BY startDate DESC")
    fun observeAll(): Flow<List<WeekEntity>>

    @Query("SELECT * FROM weeks WHERE id = :id")
    suspend fun getById(id: String): WeekEntity?

    @Query("SELECT * FROM weeks WHERE isClosed = 0 ORDER BY startDate DESC LIMIT 1")
    suspend fun getCurrentWeek(): WeekEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(week: WeekEntity)

    @Update
    suspend fun update(week: WeekEntity)

    @Query("SELECT * FROM weeks WHERE isClosed = 1 ORDER BY startDate DESC")
    fun observeClosed(): Flow<List<WeekEntity>>

    @Query("SELECT * FROM weeks WHERE isClosed = 1 ORDER BY startDate DESC LIMIT 1")
    suspend fun getMostRecentClosedWeek(): WeekEntity?

    @Query("SELECT * FROM weeks ORDER BY startDate DESC")
    suspend fun getAllSync(): List<WeekEntity>
}
