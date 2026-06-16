package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "runbook_steps",
    foreignKeys = [ForeignKey(
        entity = RunbookEntity::class,
        parentColumns = ["id"],
        childColumns = ["runbookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("runbookId")]
)
data class RunbookStepEntity(
    @PrimaryKey val id: String,
    val runbookId: String,
    val label: String,
    val stepOrder: Int
)
