package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = WeekEntity::class,
            parentColumns = ["id"],
            childColumns = ["weekId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("weekId"), Index("aspectId"), Index("categoryId"),
        Index("createdAt"), Index("completedAt"), Index("status"),
        Index("projectId")
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val weekId: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    val priority: String = "medium",
    val dueDate: String? = null,
    val hardDeadline: Boolean = false,
    val status: String = "pending",
    val resourceValue: Int = 10,
    val completedAt: String? = null,
    val carriedFromTaskId: String? = null,
    val createdAt: String,
    val isRecurring: Boolean = false,
    val estimatedMinutes: Int? = null,
    val carriedCount: Int = 0,
    val sortOrder: Int = 0,
    val isManuallyAdded: Boolean = false,
    val projectId: String? = null,
    @ColumnInfo(name = "source", defaultValue = "MANUAL")
    val source: String = "MANUAL"
)
