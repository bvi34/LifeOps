package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join row tagging a busy block as involving a person — the calendar-side counterpart to
 * task_people. Composite primary key so the same pair can't be attached twice; both sides
 * cascade, so deleting a busy block or a person cleans up the links automatically. These are the
 * people carried into a Google Calendar sync as #tags (see GoogleCalendarSyncRepository).
 */
@Entity(
    tableName = "busy_block_people",
    primaryKeys = ["busyBlockId", "personId"],
    foreignKeys = [
        ForeignKey(
            entity = BusyBlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["busyBlockId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("busyBlockId"), Index("personId")]
)
data class BusyBlockPersonEntity(
    val busyBlockId: String,
    val personId: String
)
