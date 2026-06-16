package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subtasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunbookEntity::class,
            parentColumns = ["id"],
            childColumns = ["runbookId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("taskId"), Index("runbookId")]
)
data class SubtaskEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val runbookId: String?,
    val label: String,
    val stepOrder: Int,
    val isChecked: Boolean = false
)
