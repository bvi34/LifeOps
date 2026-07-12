package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "future_project_notes",
    foreignKeys = [ForeignKey(
        entity = FutureProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class FutureProjectNoteEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val content: String,
    val createdAt: String
)
