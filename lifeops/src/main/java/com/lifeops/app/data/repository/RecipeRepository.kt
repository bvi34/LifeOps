package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.RecipeDao
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.model.RecipeNutrition
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.NutritionCalculator
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val foodItemDao: FoodItemDao
) {
    fun observeAll(): Flow<List<Recipe>> = recipeDao.observeAll().map { list -> list.map { it.toModel() } }
    fun observeById(id: String): Flow<Recipe?> = recipeDao.observeById(id).map { it?.toModel() }
    fun observeIngredients(recipeId: String): Flow<List<RecipeIngredient>> =
        recipeDao.observeIngredients(recipeId).map { list -> list.map { it.toModel() } }

    suspend fun createRecipe(name: String, servings: Double = 1.0): Recipe {
        val recipe = Recipe(UUID.randomUUID().toString(), name, servings, DateUtil.now())
        recipeDao.upsert(recipe.toEntity())
        return recipe
    }

    suspend fun updateRecipe(recipe: Recipe, name: String, servings: Double) {
        recipeDao.upsert(recipe.copy(name = name, servings = servings).toEntity())
    }

    suspend fun deleteRecipe(id: String) = recipeDao.delete(id)

    suspend fun addIngredient(recipeId: String, foodItemId: String, quantity: Double, unit: IngredientUnit): RecipeIngredient {
        val sortOrder = recipeDao.getIngredients(recipeId).size
        val ingredient = RecipeIngredient(
            id = UUID.randomUUID().toString(),
            recipeId = recipeId,
            foodItemId = foodItemId,
            quantity = quantity,
            unit = unit,
            sortOrder = sortOrder
        )
        recipeDao.upsertIngredient(ingredient.toEntity())
        return ingredient
    }

    suspend fun removeIngredient(id: String) = recipeDao.deleteIngredient(id)

    /** Recipe nutrition = sum of ingredients × quantities, so meal-plan totals are trustworthy. */
    suspend fun getNutrition(recipeId: String): RecipeNutrition? {
        val recipe = recipeDao.getById(recipeId)?.toModel() ?: return null
        val ingredients = recipeDao.getIngredients(recipeId).map { it.toModel() }
        val pairs = ingredients.mapNotNull { ingredient ->
            foodItemDao.getById(ingredient.foodItemId)?.toModel()?.let { ingredient to it }
        }
        val total = NutritionCalculator.totalFor(pairs)
        val perServing = if (recipe.servings > 0) total / recipe.servings else total
        return RecipeNutrition(recipe, total, perServing)
    }
}
