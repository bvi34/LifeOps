package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.WeeklyMenuItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeeklyMenuItemDao {
    @Query("SELECT * FROM weekly_menu_items WHERE weekStartDate = :weekStartDate ORDER BY createdAt")
    fun observeByWeek(weekStartDate: String): Flow<List<WeeklyMenuItemEntity>>

    @Query("SELECT * FROM weekly_menu_items WHERE id = :id")
    suspend fun getById(id: String): WeeklyMenuItemEntity?

    @Query("SELECT * FROM weekly_menu_items WHERE assignedDate = :date ORDER BY createdAt")
    suspend fun getByAssignedDate(date: String): List<WeeklyMenuItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WeeklyMenuItemEntity)

    @Query("DELETE FROM weekly_menu_items WHERE id = :id")
    suspend fun delete(id: String)
}
