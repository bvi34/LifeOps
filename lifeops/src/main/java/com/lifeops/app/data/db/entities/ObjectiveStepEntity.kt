package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One step on the way to an [ObjectiveEntity], in `position` order.
 *
 * A step opens when both of its gates are met: `opensOn` (null = open from the start) and, when
 * `afterPrevious` is set, the completion of the step before it. So "Complete training — open when
 * step 1 is complete — due Jul 31" is `afterPrevious = true, opensOn = null, dueDate = 2026-07-31`.
 * The rules live in [com.lifeops.app.util.Objectives].
 */
@Entity(
    tableName = "objective_steps",
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
data class ObjectiveStepEntity(
    @PrimaryKey val id: String,
    val objectiveId: String,
    val position: Int,
    val title: String,
    /** yyyy-MM-dd the step opens on; null means it is open from the start. */
    val opensOn: String? = null,
    /** Stays locked until the step before it is complete. */
    val afterPrevious: Boolean = false,
    /** yyyy-MM-dd the step is due; optional. */
    val dueDate: String? = null,
    val completedAt: String? = null
)
