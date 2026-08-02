package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pantry stock line. [foodItemId] references a LifeOps `food_items` row when matched, but is *not*
 * a Room foreign key — the two apps have separate databases in the same process, so the link is a
 * plain, nullable id we resolve at read time. Indexed on name + foodItemId (the two ways imports and
 * meal-logging look a row up).
 */
@Entity(
    tableName = "pantry_items",
    indices = [Index("name"), Index("foodItemId")]
)
data class PantryItemEntity(
    @PrimaryKey val id: String,
    val foodItemId: String?,
    val name: String,
    val quantity: Double,
    val unit: String,
    val category: String?,
    val lowStockThreshold: Double?,
    val note: String?,
    val createdAt: String,
    val updatedAt: String
)
