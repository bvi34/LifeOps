package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Indexed on name + brand: this is the table every ad-hoc entry searches against.
@Entity(
    tableName = "food_items",
    indices = [Index("name"), Index("brand"), Index("source"), Index("fdcId")]
)
data class FoodItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String?,
    val servingSize: Double,
    val servingUnit: String,
    // Gram weight of one serving, when known. Null means only SERVING-unit recipe
    // quantities are supported for this food (see RecipeIngredientEntity.unit).
    val servingSizeGrams: Double?,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val fiberG: Double?,
    val sodiumMg: Double?,
    // FoodSource: UsdaFoundation | UsdaBranded | Custom | Remembered
    val source: String,
    // Original USDA FoodData Central id, so the row can be re-synced. Null for Custom/Remembered.
    val fdcId: Long?,
    val createdAt: String
)
