package com.lifeops.app.data.db.entities

import androidx.room.*

@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(entity = AspectEntity::class, parentColumns = ["id"], childColumns = ["aspectId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("aspectId"), Index("categoryId"), Index("status")]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    @ColumnInfo(defaultValue = "'active'")
    val status: String = "active",
    val description: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
    // Set when this project was promoted from a future project; its brainstorming notes
    // stay on the archived future_projects row and are surfaced on the project detail.
    val sourceFutureProjectId: String? = null
)
