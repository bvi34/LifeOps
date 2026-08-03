package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A line on the grocery list. Like [PantryItemEntity], [foodItemId] is a soft, nullable link to a
 * LifeOps catalog food (not a Room foreign key — separate databases in the same process). Indexed on
 * name (dedupe on add) and foodItemId (restock the right pantry row on purchase). See
 * [com.logistics.app.data.model.GroceryItem].
 */
@Entity(
    tableName = "grocery_items",
    indices = [Index("name"), Index("foodItemId"), Index("checked")]
)
data class GroceryItemEntity(
    @PrimaryKey val id: String,
    val foodItemId: String?,
    val name: String,
    val quantity: Double,
    val unit: String,
    val category: String?,
    val source: String,
    val recipeId: String?,
    val checked: Boolean,
    val note: String?,
    val createdAt: String,
    val updatedAt: String
)
