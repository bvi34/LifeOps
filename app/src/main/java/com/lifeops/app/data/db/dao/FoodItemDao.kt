package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.FoodItemEntity

@Dao
interface FoodItemDao {
    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun getById(id: String): FoodItemEntity?

    @Query("SELECT * FROM food_items WHERE fdcId = :fdcId LIMIT 1")
    suspend fun getByFdcId(fdcId: Long): FoodItemEntity?

    // Exact-prefix name matches first, then substring matches anywhere in name/brand —
    // this is the table that gets hit on every ad-hoc entry, so cheap ordering matters.
    @Query(
        """
        SELECT * FROM food_items
        WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END,
            name
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<FoodItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FoodItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FoodItemEntity>)

    @Query("DELETE FROM food_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM food_items WHERE source = :source")
    suspend fun countBySource(source: String): Int
}
