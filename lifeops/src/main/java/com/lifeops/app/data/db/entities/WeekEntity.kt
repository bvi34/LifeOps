package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weeks")
data class WeekEntity(
    @PrimaryKey val id: String,
    val startDate: String,
    val endDate: String,
    val isClosed: Boolean = false,
    val closedAt: String? = null
)
