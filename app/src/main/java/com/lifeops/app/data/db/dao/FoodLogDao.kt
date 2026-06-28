package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.FoodLogEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodLogDao {
    @Insert
    suspend fun insert(entry: FoodLogEntryEntity)

    @Update
    suspend fun update(entry: FoodLogEntryEntity)

    @Query("SELECT * FROM food_log_entries WHERE id = :id")
    suspend fun getById(id: String): FoodLogEntryEntity?

    @Query("SELECT * FROM food_log_entries WHERE weeklyMenuItemId = :weeklyMenuItemId")
    suspend fun getByWeeklyMenuItemId(weeklyMenuItemId: String): FoodLogEntryEntity?

    @Query("DELETE FROM food_log_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM food_log_entries ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FoodLogEntryEntity>

    // Half-open [startIso, endIso) range — callers pass a day's start/next-day's start so a
    // local calendar day's entries are returned regardless of what hour they're stamped at.
    @Query("SELECT * FROM food_log_entries WHERE loggedAt >= :startIso AND loggedAt < :endIso ORDER BY loggedAt ASC")
    fun observeByDateRange(startIso: String, endIso: String): Flow<List<FoodLogEntryEntity>>

    // Most-recently-logged distinct foods — surfaced at the top of ad-hoc search.
    @Query(
        """
        SELECT fi.* FROM food_items fi
        INNER JOIN (
            SELECT foodItemId, MAX(loggedAt) AS lastLoggedAt
            FROM food_log_entries
            WHERE foodItemId IS NOT NULL
            GROUP BY foodItemId
        ) recent ON recent.foodItemId = fi.id
        ORDER BY recent.lastLoggedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentFoodItems(limit: Int): List<FoodItemEntity>

    // Most frequently logged foods since a cutoff date — same 30-40 foods on repeat.
    @Query(
        """
        SELECT fi.* FROM food_items fi
        INNER JOIN (
            SELECT foodItemId, COUNT(*) AS uses
            FROM food_log_entries
            WHERE foodItemId IS NOT NULL AND loggedAt >= :since
            GROUP BY foodItemId
        ) freq ON freq.foodItemId = fi.id
        ORDER BY freq.uses DESC
        LIMIT :limit
        """
    )
    suspend fun getFrequentFoodItems(since: String, limit: Int): List<FoodItemEntity>
}
