package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "time_entries",
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("taskId"), Index("recordedAt")]
)
data class TimeEntryEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val durationMinutes: Int,
    val note: String? = null,
    val recordedAt: String
)
