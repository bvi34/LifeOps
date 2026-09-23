package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An Objective: a forward-looking goal with a due date, reached through an ordered run of steps
 * (see [ObjectiveStepEntity]) and closed only by an outcome — success reported, or marked
 * unsuccessful. Unlike a task it belongs to no week: while `status` is 'active' it is shown above
 * its aspect every week until one of those two outcomes closes it.
 *
 * `aspectId` is a nullable FK with ON DELETE SET NULL (the same shape as milestones.aspectId), so
 * deleting an aspect leaves the objective standing, shown with the unfiled work. No
 * @ColumnInfo(defaultValue) on any column, so the CREATE in MIGRATION_55_56 must match exactly.
 */
@Entity(
    tableName = "objectives",
    foreignKeys = [
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("aspectId"), Index("status")]
)
data class ObjectiveEntity(
    @PrimaryKey val id: String,
    val title: String,
    val aspectId: String? = null,
    /** yyyy-MM-dd — when the objective is due, and when its success criteria is due. */
    val dueDate: String,
    /** What "complete" means, e.g. "Success reported". Reported against [dueDate]. */
    val successCriteria: String,
    /** 'active', 'succeeded' or 'unsuccessful'. */
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    /** When the outcome was recorded; null while active. */
    val closedAt: String? = null
)
