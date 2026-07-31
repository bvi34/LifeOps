package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.WeekSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeekSnapshotDao {
    @Query("SELECT * FROM week_snapshots WHERE weekId = :weekId LIMIT 1")
    suspend fun getByWeekId(weekId: String): WeekSnapshotEntity?

    @Query("SELECT * FROM week_snapshots ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WeekSnapshotEntity>>

    @Query("SELECT * FROM week_snapshots WHERE createdAt >= :since ORDER BY createdAt DESC")
    fun observeSince(since: String): Flow<List<WeekSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: WeekSnapshotEntity)

    @Query("SELECT * FROM week_snapshots")
    suspend fun getAll(): List<WeekSnapshotEntity>
}
