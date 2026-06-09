package com.lifeops.app.data.db.entities

import androidx.room.*

@Entity(
    tableName = "task_cost_entries",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CostResourceEntity::class, parentColumns = ["id"], childColumns = ["resourceId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("taskId"), Index("resourceId")]
)
data class TaskCostEntryEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val resourceId: String,
    val amount: Int,
    val note: String? = null,
    val recordedAt: String
)
