package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An image attached to a task ("task instructions with pictures", a family-album shot, …). The
 * image is stored as a downscaled base64 JPEG string rather than a file path so it rides along in
 * both the JSON backup and Android's Auto Backup with no separate file lifecycle to manage — a row
 * deleted (or a task cascade-deleted) takes its image with it. Downscaling on import (see
 * TaskAttachmentRepository) keeps each image small against the Auto Backup size budget.
 */
@Entity(
    tableName = "task_attachments",
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("taskId")]
)
data class TaskAttachmentEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val imageData: String,
    val caption: String? = null,
    val createdAt: String
)
