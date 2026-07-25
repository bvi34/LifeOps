package com.lifeops.app.connection.service

import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.repository.RecipeRepository

/** Use-case layer for recipes and their ingredients. */
class RecipeService(private val recipeRepository: RecipeRepository) {

    suspend fun create(name: String, servings: Double = 1.0): Recipe {
        require(name.isNotBlank()) { "Recipe name must not be blank" }
        return recipeRepository.createRecipe(name.trim(), servings.coerceAtLeast(0.0))
    }

    suspend fun delete(id: String) = recipeRepository.deleteRecipe(id)

    suspend fun addIngredient(
        recipeId: String,
        foodItemId: String,
        quantity: Double,
        unit: IngredientUnit
    ): RecipeIngredient = recipeRepository.addIngredient(recipeId, foodItemId, quantity, unit)

    suspend fun removeIngredient(id: String) = recipeRepository.removeIngredient(id)
}
