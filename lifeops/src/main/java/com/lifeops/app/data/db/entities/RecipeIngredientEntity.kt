package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recipe_ingredients",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodItemEntity::class, parentColumns = ["id"], childColumns = ["foodItemId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("recipeId"), Index("foodItemId")]
)
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val foodItemId: String,
    val quantity: Double,
    // IngredientUnit: GRAM | SERVING (strict — see NutritionCalculator)
    val unit: String,
    val sortOrder: Int
)
