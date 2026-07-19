package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A place the user tracks weather for. Standalone table — no FKs into the rest of the schema —
 * so the whole weather feature is additive and can't disturb existing tasks/aspects data.
 */
@Entity(tableName = "weather_locations")
data class WeatherLocationEntity(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: String
)
