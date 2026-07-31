package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A cached weather reading for a location. The "current conditions" live as flat columns (so the
 * widget/cards can read them without deserializing anything), while the full hourly and daily
 * forecast period lists ride along as Gson JSON blobs — enough to rebuild an entire WeatherReport
 * from cache with zero network. One row = one refresh; the repository keeps the latest and prunes
 * older ones. Deleting a location cascades its snapshots away.
 */
@Entity(
    tableName = "weather_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = WeatherLocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("locationId")]
)
data class WeatherSnapshotEntity(
    @PrimaryKey val id: String,
    val locationId: String,
    val fetchedAt: String,
    val observedAt: String,
    val temperatureF: Int,
    val feelsLikeF: Int,
    val humidityPct: Int?,
    val windSpeedMph: Int,
    val windDirection: String?,
    val windGustMph: Int?,
    val uvIndex: Int?,
    val precipitationProbabilityPct: Int?,
    val shortForecast: String,
    /** Gson-serialized List<ForecastPeriod> for the hourly product. */
    val hourlyJson: String,
    /** Gson-serialized List<ForecastPeriod> for the day/night product. */
    val dailyJson: String
)
