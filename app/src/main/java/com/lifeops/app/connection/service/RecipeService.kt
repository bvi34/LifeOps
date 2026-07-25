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

    /** Rename / re-portion a recipe. The detail screen already holds the [recipe]. */
    suspend fun update(recipe: Recipe, name: String, servings: Double): Recipe {
        require(name.isNotBlank()) { "Recipe name must not be blank" }
        val newName = name.trim()
        recipeRepository.updateRecipe(recipe, newName, servings.coerceAtLeast(0.0))
        return recipe.copy(name = newName, servings = servings.coerceAtLeast(0.0))
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
