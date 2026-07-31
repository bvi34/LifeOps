package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.PhoneActivityEventEntity

@Dao
interface PhoneActivityEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PhoneActivityEventEntity)

    /** Every event inside [startMillis, endMillis], oldest first — the overnight reconstruction window. */
    @Query(
        "SELECT * FROM phone_activity_events WHERE occurredAt >= :startMillis AND occurredAt <= :endMillis " +
            "ORDER BY occurredAt ASC"
    )
    suspend fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneActivityEventEntity>

    /** Housekeeping: drop raw events older than [beforeMillis] (already folded into sleep reports). */
    @Query("DELETE FROM phone_activity_events WHERE occurredAt < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)

    @Query("SELECT COUNT(*) FROM phone_activity_events")
    suspend fun count(): Int
}
