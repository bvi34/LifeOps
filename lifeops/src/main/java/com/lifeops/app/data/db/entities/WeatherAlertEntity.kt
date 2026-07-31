package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached active NWS alert for a location. Primary key is the NWS alert id, so re-fetching the
 * same alert REPLACEs rather than duplicates. [severity] stores the AlertSeverity.value string;
 * [expires] is the ISO instant the refresh worker prunes against. Cascades on location delete.
 */
@Entity(
    tableName = "weather_alerts",
    foreignKeys = [
        ForeignKey(
            entity = WeatherLocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("locationId"), Index("expires")]
)
data class WeatherAlertEntity(
    @PrimaryKey val id: String,
    val locationId: String,
    val event: String,
    val severity: String,
    val headline: String?,
    val description: String?,
    val instruction: String?,
    val onset: String?,
    val expires: String?,
    val areaDesc: String?,
    val fetchedAt: String
)
