package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Macros are snapshotted at log time (same pattern as WeekSnapshot.aspectHistory) so editing
// a FoodItem later, or re-syncing it from USDA, can't rewrite past diary history.
@Entity(
    tableName = "food_log_entries",
    foreignKeys = [
        ForeignKey(entity = FoodItemEntity::class, parentColumns = ["id"], childColumns = ["foodItemId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = WeeklyMenuItemEntity::class, parentColumns = ["id"], childColumns = ["weeklyMenuItemId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("foodItemId"), Index("loggedAt"), Index("weeklyMenuItemId")]
)
data class FoodLogEntryEntity(
    @PrimaryKey val id: String,
    // Null for an ad-hoc entry with no saved FoodItem behind it.
    val foodItemId: String?,
    val name: String,
    val quantity: Double,
    val unit: String,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val loggedAt: String,
    val source: String,
    val confirmed: Boolean,
    val confirmedAt: String?,
    // Null unless this entry originated from (or was later linked back to) a WeeklyMenuItem.
    val weeklyMenuItemId: String?
)
