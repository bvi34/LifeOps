package com.logistics.app.data.repository

import android.content.Context
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.model.RecipeNutrition
import com.lifeops.app.data.repository.FoodItemRepository
import com.lifeops.app.data.repository.RecipeRepository
import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.logic.GroceryPlanner
import com.logistics.app.logic.IngredientLineParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Logistics' read/write bridge into LifeOps' food & recipe catalog. This is the seam that makes the
 * user's ask — "take the food, recipes, etc. from LifeOps" — real: rather than keep its own copies,
 * Logistics reads LifeOps' `food_items` / `recipes` (same process, its database) and writes new
 * recipes and custom foods back into that one catalog, so both apps stay in sync by construction.
 *
 * It wraps LifeOps' own [RecipeRepository]/[FoodItemRepository], built from the shared
 * [LifeOpsDatabase] singleton.
 */
class LifeOpsCatalog private constructor(
    private val recipeRepo: RecipeRepository,
    private val foodItemRepo: FoodItemRepository
) {

    // --- reads ---
    fun observeRecipes(): Flow<List<Recipe>> = recipeRepo.observeAll()
    fun observeIngredients(recipeId: String): Flow<List<RecipeIngredient>> =
        recipeRepo.observeIngredients(recipeId)
    suspend fun getRecipeNutrition(recipeId: String): RecipeNutrition? = recipeRepo.getNutrition(recipeId)
    suspend fun getFood(id: String): FoodItem? = foodItemRepo.getById(id)
    suspend fun searchFood(query: String, limit: Int = 25): List<FoodItem> = foodItemRepo.search(query, limit)

    /** Case-insensitive exact-name match against the catalog, used to link a pantry line to a known
     *  food so nutrition and recipes line up. Null when nothing matches closely enough. */
    suspend fun findFoodByName(name: String): FoodItem? =
        foodItemRepo.search(name, 5).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * The catalog foods a recipe calls for, resolved to name + id — the raw material the grocery-list
     * builder diffs against the pantry to find what's missing. Ingredients whose food no longer
     * exists in the catalog are dropped.
     */
    suspend fun ingredientFoods(recipeId: String): List<GroceryPlanner.NeededFood> =
        recipeRepo.observeIngredients(recipeId).first().mapNotNull { ing ->
            foodItemRepo.getById(ing.foodItemId)?.let { GroceryPlanner.NeededFood(it.id, it.name) }
        }

    // --- writes (materialize an imported recipe into LifeOps) ---

    /**
     * Create a LifeOps recipe from a parsed web import. Each ingredient line is split by
     * [IngredientLineParser]; its name becomes (or reuses) a custom food, and it's attached to the
     * recipe. Imported units (cups, tbsp, …) don't fit LifeOps' strict GRAM/SERVING ingredient math,
     * so the parsed unit is preserved on the food's serving unit and the ingredient is stored as a
     * SERVING quantity — the ingredient list stays faithful even when macros are unknown (0).
     */
    suspend fun createImportedRecipe(parsed: ParsedRecipe): Recipe {
        val recipe = recipeRepo.createRecipe(parsed.name, parsed.servings ?: 1.0)
        for (raw in parsed.ingredients) {
            val ing = IngredientLineParser.parse(raw)
            if (ing.name.isBlank()) continue
            val food = findFoodByName(ing.name) ?: foodItemRepo.createCustomFood(
                name = ing.name,
                brand = null,
                servingSize = 1.0,
                servingUnit = ing.unit ?: "serving",
                servingSizeGrams = null,
                calories = 0.0,
                carbsG = 0.0,
                proteinG = 0.0,
                fatG = 0.0
            )
            recipeRepo.addIngredient(recipe.id, food.id, ing.quantity ?: 1.0, IngredientUnit.SERVING)
        }
        return recipe
    }

    companion object {
        fun create(context: Context): LifeOpsCatalog {
            val db = LifeOpsDatabase.getInstance(context)
            val recipeRepo = RecipeRepository(db.recipeDao(), db.foodItemDao())
            val foodItemRepo = FoodItemRepository(db.foodItemDao())
            return LifeOpsCatalog(recipeRepo, foodItemRepo)
        }
    }
}
