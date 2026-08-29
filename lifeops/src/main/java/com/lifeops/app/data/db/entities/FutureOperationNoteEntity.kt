package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "future_operation_notes",
    foreignKeys = [ForeignKey(
        entity = FutureOperationEntity::class,
        parentColumns = ["id"],
        childColumns = ["operationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("operationId")]
)
data class FutureOperationNoteEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val content: String,
    val createdAt: String
)
