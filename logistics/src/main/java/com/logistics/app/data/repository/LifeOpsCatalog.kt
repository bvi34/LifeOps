package com.logistics.app.data.repository

import android.content.Context
import com.lifeops.app.connection.service.FoodService
import com.lifeops.app.connection.service.MealPlanService
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.model.RecipeNutrition
import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.data.repository.FoodItemRepository
import com.lifeops.app.data.repository.FoodLogRepository
import com.lifeops.app.data.repository.RecipeRepository
import com.lifeops.app.data.repository.WeeklyMenuRepository
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.NutritionCalculator
import com.logistics.app.data.model.IngredientRow
import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.logic.GroceryPlanner
import com.logistics.app.logic.IngredientLineParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Logistics' read/write bridge into LifeOps' food & recipe catalog. This is the seam that makes the
 * user's ask — "take the food, recipes, etc. from LifeOps" — real: rather than keep its own copies,
 * Logistics reads LifeOps' `food_items` / `recipes` (same process, its database) and writes new
 * recipes and custom foods back into that one catalog, so both apps stay in sync by construction.
 *
 * It wraps LifeOps' own [RecipeRepository]/[FoodItemRepository], built from the shared
 * [LifeOpsDatabase] singleton.
 *
 * ### The diary and the macros come the same way
 *
 * Logistics' **Food** tab is the same food-and-calorie surface LifeOps has — the day's diary, its
 * planned-vs-confirmed totals, Confirm/Adjust, ad-hoc entries, custom foods, and planning a recipe
 * onto a day — and it is that surface, not a second one: every write goes through LifeOps'
 * [FoodService] and [MealPlanService] into LifeOps' `food_log_entries` and `weekly_menu_items`. Log
 * a bowl of chili in Logistics and it is in LifeOps' Daily Plan; confirm it in LifeOps and Logistics
 * shows it confirmed. One diary, two doors into it — the same rule the pantry already follows for
 * foods and recipes, and the reason the calorie maths ([NutritionCalculator]) is imported rather
 * than re-derived here.
 */
class LifeOpsCatalog private constructor(
    private val recipeRepo: RecipeRepository,
    private val foodItemRepo: FoodItemRepository,
    private val foodLogRepo: FoodLogRepository,
    private val foodService: FoodService,
    private val mealPlanService: MealPlanService
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

    /**
     * A recipe's ingredient lines resolved for display, each carrying the reason it contributes
     * nothing to the totals when it doesn't. Recipes imported from a link (or a screenshot) arrive
     * as zero-macro placeholder foods, so a quietly-summed calorie count would be fiction — the
     * printed total is a **floor**, and these rows say which lines are missing from it.
     */
    suspend fun ingredientRows(recipeId: String): List<IngredientRow> =
        recipeRepo.observeIngredients(recipeId).first().map { ingredient ->
            val food = foodItemRepo.getById(ingredient.foodItemId)
            IngredientRow(
                id = ingredient.id,
                foodName = food?.name ?: "Unknown food",
                quantity = ingredient.quantity,
                unit = ingredient.unit.name.lowercase(),
                gapLabel = NutritionCalculator.macroGap(food, ingredient.quantity, ingredient.unit)?.label
            )
        }

    // --- the food diary (LifeOps' own, opened from Logistics) ---

    /** Every diary entry on the local day [date] (yyyy-MM-dd), planned and logged alike. */
    fun observeDiary(date: String): Flow<List<FoodLogEntry>> = foodLogRepo.observeForDate(date)

    /** Diary entries logged on/after [startIso], for the rolling per-day averages. */
    suspend fun entriesSince(startIso: String): List<FoodLogEntry> = foodLogRepo.getEntriesSince(startIso)

    /** Most real logging is the same few dozen foods on repeat, so both lists lead the search box. */
    suspend fun recentFoods(limit: Int = 12): List<FoodItem> = foodLogRepo.getRecentFoodItems(limit)
    suspend fun frequentFoods(limit: Int = 12): List<FoodItem> = foodLogRepo.getFrequentFoodItems(limit)

    /**
     * Logs a quantity of a catalog food onto [date] (yyyy-MM-dd; today when null). Null when the
     * food is gone or the unit can't resolve. The day matters because the diary is read a day at a
     * time: an entry typed while looking at Friday belongs on Friday, not on today.
     */
    suspend fun logFood(
        foodItemId: String,
        quantity: Double,
        unit: IngredientUnit,
        date: String? = null
    ): FoodLogEntry? = foodService.logFood(foodItemId, quantity, unit, instantFor(date))

    /** A one-off entry with typed-in macros — the escape hatch for anything not in the catalog. */
    suspend fun logAdHoc(
        name: String,
        quantity: Double,
        unit: IngredientUnit,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        date: String? = null
    ): FoodLogEntry =
        foodService.logAdHoc(name, quantity, unit, calories, carbsG, proteinG, fatG, instantFor(date))

    /**
     * The instant to stamp an entry with: **now** for today (so the diary reads in the order things
     * were eaten) and midday for any other day, which is where a meal with no stated time belongs —
     * the same convention `MealPlanService` uses for an untyped planned meal.
     */
    private fun instantFor(date: String?): String? {
        if (date == null || date == LocalDate.now().toString()) return null
        if (!DateUtil.isValidDate(date)) return null
        return DateUtil.isoFromEpoch(DateUtil.epochMillisForDate(date, MIDDAY))
    }

    suspend fun confirmEntry(entryId: String): FoodLogEntry? = foodService.confirmEntry(entryId)

    suspend fun adjustEntry(entryId: String, quantity: Double, unit: IngredientUnit): FoodLogEntry? =
        foodService.adjustEntry(entryId, quantity, unit)

    /** Turns a one-off entry into a permanent, searchable custom food — one tap, no retyping. */
    suspend fun promoteToCustomFood(entryId: String): FoodItem? = foodService.promoteToCustomFood(entryId)

    suspend fun createCustomFood(
        name: String,
        servingSize: Double,
        servingUnit: String,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double
    ): FoodItem = foodService.createCustomFood(
        FoodService.CustomFoodInput(
            name = name,
            servingSize = servingSize,
            servingUnit = servingUnit,
            calories = calories,
            carbsG = carbsG,
            proteinG = proteinG,
            fatG = fatG
        )
    )

    /** Commits a recipe to a day: a menu line plus the planned (unconfirmed) diary entry it stands
     *  for, carrying the recipe's macros for the servings planned. */
    suspend fun planMeal(date: String, recipeId: String, servings: Double, mealType: String?): WeeklyMenuItem =
        mealPlanService.plan(date = date, recipeId = recipeId, servings = servings, mealType = mealType)

    /** Takes a planned meal back off the day. A confirmed entry stays — by then it's history. */
    suspend fun unplanMeal(menuItemId: String): Boolean = mealPlanService.unplan(menuItemId)

    /**
     * Logs what a cooked meal came to, as a confirmed entry — the join between the two halves of
     * Logistics. Deducting a recipe from the pantry already says what you ate; this puts the
     * calories for it in the diary without retyping them. Null when the recipe has no resolvable
     * macros at all (an import whose ingredients are all placeholders), in which case there is
     * nothing honest to log.
     */
    suspend fun logCookedRecipe(mealName: String, recipeId: String, servings: Double): FoodLogEntry? {
        val perServing = recipeRepo.getNutrition(recipeId)?.perServing ?: return null
        if (perServing == NutritionTotals.ZERO) return null
        val totals = perServing * servings
        return foodService.logAdHoc(
            name = mealName,
            quantity = servings,
            unit = IngredientUnit.SERVING,
            calories = totals.calories,
            carbsG = totals.carbsG,
            proteinG = totals.proteinG,
            fatG = totals.fatG
        )
    }

    // --- writes (materialize an imported recipe into LifeOps) ---

    /**
     * Create a LifeOps recipe from a parsed web import. Each ingredient line is split by
     * [IngredientLineParser]; its name becomes (or reuses) a custom food, and it's attached to the
     * recipe. Imported units (cups, tbsp, …) don't fit LifeOps' strict GRAM/SERVING ingredient math,
     * so the parsed unit is preserved on the food's serving unit and the ingredient is stored as a
     * SERVING quantity — the ingredient list stays faithful even when macros are unknown (0), and
     * LifeOps' recipe screen flags those lines rather than quietly summing them as zero.
     *
     * The method and the source link come across too, so the imported recipe is something you can
     * cook from and trace back — not a shopping list with a name on it.
     */
    suspend fun createImportedRecipe(parsed: ParsedRecipe): Recipe {
        val recipe = recipeRepo.createRecipe(
            name = parsed.name,
            servings = parsed.servings ?: 1.0,
            instructions = parsed.steps.joinToString("\n").takeIf { it.isNotBlank() },
            sourceUrl = parsed.sourceUrl
        )
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
        /** Where a meal with no stated time lands on a day that isn't today. */
        private const val MIDDAY = 12

        fun create(context: Context): LifeOpsCatalog {
            val db = LifeOpsDatabase.getInstance(context)
            val recipeRepo = RecipeRepository(db.recipeDao(), db.foodItemDao())
            val foodItemRepo = FoodItemRepository(db.foodItemDao())
            val foodLogRepo = FoodLogRepository(db.foodLogDao(), db.foodItemDao())
            val menuRepo = WeeklyMenuRepository(db.weeklyMenuItemDao())
            return LifeOpsCatalog(
                recipeRepo = recipeRepo,
                foodItemRepo = foodItemRepo,
                foodLogRepo = foodLogRepo,
                foodService = FoodService(foodItemRepo, foodLogRepo),
                mealPlanService = MealPlanService(menuRepo, recipeRepo, foodLogRepo)
            )
        }
    }
}
