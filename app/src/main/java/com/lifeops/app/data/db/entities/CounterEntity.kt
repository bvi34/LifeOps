package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "counters",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("categoryId")]
)
data class CounterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String,
    // A counter flagged as a habit surfaces on the habit dashboard and can carry its own
    // daily reminder. Added via ALTER (MIGRATION_38_39) with a SQL DEFAULT, so it needs a
    // matching @ColumnInfo(defaultValue) or Room's schema validation fails at startup.
    @ColumnInfo(defaultValue = "0")
    val isHabit: Boolean = false,
    // Optional device-local hour (0-23) at which this habit fires its own daily reminder.
    // Null = no reminder. Nullable, so it needs no SQL default.
    val reminderHour: Int? = null
)
