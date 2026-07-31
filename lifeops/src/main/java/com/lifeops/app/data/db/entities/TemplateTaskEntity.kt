package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "template_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunbookEntity::class,
            parentColumns = ["id"],
            childColumns = ["runbookId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("templateId"), Index("runbookId")]
)
data class TemplateTaskEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val title: String,
    val aspectName: String? = null,
    val categoryName: String? = null,
    @ColumnInfo(defaultValue = "'medium'")
    val priority: String = "medium",
    val estimatedMinutes: Int? = null,
    val runbookId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val taskOrder: Int = 0
)
