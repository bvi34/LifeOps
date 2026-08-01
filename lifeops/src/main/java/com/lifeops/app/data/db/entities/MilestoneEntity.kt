package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Milestone: a rare, once-in-a-lifetime accomplishment worth marking on the record. Unlike a
 * task, it is not planned or estimated — it is recorded after the fact and, unlike everything else
 * in the economy, its points are granted the moment it's logged (see MilestoneRepository), not at
 * week-close. It optionally attaches to an aspect and/or a person; the aspect attachment is what
 * routes its points into that aspect's mapped game resources.
 *
 * Both attachments are nullable FKs with ON DELETE SET NULL (the same shape as tasks.projectId),
 * so deleting an aspect or person leaves the milestone standing with its attachment cleared. No
 * @ColumnInfo(defaultValue) on any column, so the CREATE in MIGRATION_44_45 must match exactly.
 */
@Entity(
    tableName = "milestones",
    foreignKeys = [
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("aspectId"), Index("personId")]
)
data class MilestoneEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    /** Points granted immediately on creation, minted into the attached aspect's mapped resources. */
    val points: Int = 0,
    val aspectId: String? = null,
    val personId: String? = null,
    /** When the accomplishment happened (yyyy-MM-dd or full timestamp). */
    val achievedAt: String,
    val createdAt: String
)
