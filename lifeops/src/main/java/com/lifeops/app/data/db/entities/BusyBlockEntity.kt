package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An in-app "busy" window used for free/busy planning — the workaround for calendars (e.g. a work
 * Outlook calendar) that never sync into Android's system provider.
 *
 * Ownership: [personId] is null for the user's own schedule, or a person's id for that household
 * member's schedule (FK, cascade-deletes with the person). The best-time engine treats a window as
 * unavailable when it overlaps the user's own busy time OR any involved person's busy time, so a
 * task can be gated on "weather AND everyone's schedule permit".
 *
 * Recurrence: a block is either weekly-recurring — [daysMask] has the bit set for each weekday it
 * repeats on (bit 0 = Monday … bit 6 = Sunday), [specificDate] null — or one-off — [specificDate]
 * is a yyyy-MM-dd date and [daysMask] is 0. [startMinutes]/[endMinutes] are minutes from local
 * midnight.
 */
@Entity(
    tableName = "busy_blocks",
    foreignKeys = [ForeignKey(
        entity = PersonEntity::class,
        parentColumns = ["id"],
        childColumns = ["personId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("personId")]
)
data class BusyBlockEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val daysMask: Int,
    val specificDate: String? = null,
    val personId: String? = null,
    val createdAt: String,
    // Whether a start-of-block reminder is scheduled. NOT NULL with a matching
    // @ColumnInfo(defaultValue) so the additive migration passes Room's schema validation.
    @ColumnInfo(defaultValue = "0")
    val reminderEnabled: Boolean = false
)
