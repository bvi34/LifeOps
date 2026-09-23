package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note on an [ObjectiveEntity] itself — the thinking that belongs to the goal rather than to any
 * one step (a step's notes live on its week tasks, like an operation's). Deleted with the objective.
 * No @ColumnInfo(defaultValue), so the CREATE in MIGRATION_56_57 must match exactly.
 */
@Entity(
    tableName = "objective_notes",
    foreignKeys = [
        ForeignKey(
            entity = ObjectiveEntity::class,
            parentColumns = ["id"],
            childColumns = ["objectiveId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("objectiveId")]
)
data class ObjectiveNoteEntity(
    @PrimaryKey val id: String,
    val objectiveId: String,
    val content: String,
    val createdAt: String
)
