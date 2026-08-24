package com.lifeops.app.connection.service

import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.repository.RecipeRepository

/** Use-case layer for recipes and their ingredients. */
class RecipeService(private val recipeRepository: RecipeRepository) {

    suspend fun create(
        name: String,
        servings: Double = 1.0,
        instructions: String? = null,
        sourceUrl: String? = null
    ): Recipe {
        require(name.isNotBlank()) { "Recipe name must not be blank" }
        return recipeRepository.createRecipe(
            name = name.trim(),
            servings = servings.coerceAtLeast(0.0),
            instructions = clean(instructions),
            sourceUrl = clean(sourceUrl)
        )
    }

    /**
     * Rename / re-portion a recipe and edit its method or source. Every field is optional and
     * follows one rule: **null leaves it alone, blank clears it** — so a caller that only knows
     * about the name (an older route call, a script) can never wipe the instructions by omission,
     * while the edit screen can still empty a field the user cleared.
     *
     * Returns null when no recipe has that id.
     */
    suspend fun update(
        id: String,
        name: String? = null,
        servings: Double? = null,
        instructions: String? = null,
        sourceUrl: String? = null
    ): Recipe? {
        val existing = recipeRepository.getById(id) ?: return null
        val newName = name?.trim()?.takeIf { it.isNotBlank() } ?: existing.name
        val updated = existing.copy(
            name = newName,
            servings = servings?.coerceAtLeast(0.0) ?: existing.servings,
            instructions = if (instructions == null) existing.instructions else clean(instructions),
            sourceUrl = if (sourceUrl == null) existing.sourceUrl else clean(sourceUrl)
        )
        recipeRepository.updateRecipe(updated)
        return updated
    }

    /** Trim free text, and treat "nothing left" as absent rather than as an empty string. */
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

    suspend fun delete(id: String) = recipeRepository.deleteRecipe(id)

    suspend fun addIngredient(
        recipeId: String,
        foodItemId: String,
        quantity: Double,
        unit: IngredientUnit
    ): RecipeIngredient = recipeRepository.addIngredient(recipeId, foodItemId, quantity, unit)

    suspend fun removeIngredient(id: String) = recipeRepository.removeIngredient(id)
}
