package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join row marking a task as involving a person (many-to-many). Composite primary key so the
 * same pair can't be attached twice; both sides cascade, so deleting a task or a person cleans
 * up the links automatically.
 */
@Entity(
    tableName = "task_people",
    primaryKeys = ["taskId", "personId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index("personId")]
)
data class TaskPersonEntity(
    val taskId: String,
    val personId: String
)
