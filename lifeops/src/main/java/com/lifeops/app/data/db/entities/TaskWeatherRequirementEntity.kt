package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 1:1 weather constraints for a task (Phase 3). taskId is the primary key, so a task has at most
 * one requirement row; deleting the task cascades it away. Kept as a side table so the core
 * `tasks` schema and its 12-argument create/edit pipeline stay untouched.
 */
@Entity(
    tableName = "task_weather_requirements",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaskWeatherRequirementEntity(
    @PrimaryKey val taskId: String,
    val outdoorPreferred: Boolean,
    val durationMinutes: Int?,
    val maxTempF: Int?,
    val minTempF: Int?,
    val avoidRain: Boolean,
    val maxWindMph: Int?
)
