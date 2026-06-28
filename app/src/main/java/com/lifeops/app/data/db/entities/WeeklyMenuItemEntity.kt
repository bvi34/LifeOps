package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// A meal committed to a week before it's necessarily assigned to a day. recipeId is nullable
// so "meatloaf" can be jotted down before a Recipe exists for it — mealName is the freeform
// fallback used whenever recipeId is null (and kept as a label even when it's set).
@Entity(
    tableName = "weekly_menu_items",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("weekStartDate"), Index("recipeId"), Index("assignedDate")]
)
data class WeeklyMenuItemEntity(
    @PrimaryKey val id: String,
    val weekStartDate: String,
    val recipeId: String?,
    val mealName: String,
    val plannedServings: Double,
    val assignedDate: String?,
    val mealType: String?,
    val createdAt: String
)
