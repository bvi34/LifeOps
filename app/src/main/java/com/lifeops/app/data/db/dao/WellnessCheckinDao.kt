package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.WellnessCheckinEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WellnessCheckinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkin: WellnessCheckinEntity)

    /** Newest first — the recent-entries feed on the report screen. */
    @Query("SELECT * FROM wellness_checkins ORDER BY recordedAt DESC")
    fun observeAll(): Flow<List<WellnessCheckinEntity>>

    /** Everything from [sinceWeekKey] onward, newest first — the report's working window. */
    @Query("SELECT * FROM wellness_checkins WHERE weekKey >= :sinceWeekKey ORDER BY recordedAt DESC")
    fun observeSince(sinceWeekKey: Int): Flow<List<WellnessCheckinEntity>>

    /** Count of entries of a [kind] logged on [dayKey] — gates the daily pop-ups. */
    @Query("SELECT COUNT(*) FROM wellness_checkins WHERE dayKey = :dayKey AND kind = :kind")
    suspend fun countForDay(dayKey: String, kind: String): Int

    @Query("SELECT * FROM wellness_checkins WHERE dayKey = :dayKey ORDER BY recordedAt ASC")
    suspend fun getForDay(dayKey: String): List<WellnessCheckinEntity>
}
