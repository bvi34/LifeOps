package com.lifeops.app.util

import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.RecipeIngredient

/**
 * Why an ingredient line adds nothing to a recipe's totals. Each one makes the printed total a
 * *floor* rather than a sum, which is worth saying out loud: web-imported ingredients arrive as
 * zero-macro placeholder foods ([NO_MACROS]) precisely because the page published a name and no
 * nutrition, so a quietly-summed "312 kcal" would be fiction.
 */
enum class MacroGap(val label: String) {
    /** The ingredient's food row is gone (deleted from the catalog since). */
    MISSING_FOOD("food no longer in the catalog"),
    /** Grams were asked for, but the food has no known serving weight to convert against. */
    UNRESOLVED_UNIT("no serving weight — grams can't be converted"),
    /** The food is known but every macro on it is zero, so it contributes nothing. */
    NO_MACROS("macros not recorded")
}

/**
 * Strict gram/serving math for recipe ingredients. USDA nutrients are per-serving (already
 * normalized from per-100g at import time); recipes think in cups/tbsp/whole-items. Rather
 * than guess a conversion, an ingredient is either a multiple of the food's serving, or a
 * gram quantity resolved against the food's known serving weight. Friendlier units come later.
 */
object NutritionCalculator {
    /** Multiplier of the food's per-serving macros this ingredient quantity represents, or
     *  null if the unit can't be resolved (GRAM requested but the food has no known gram
     *  weight per serving). */
    fun factorFor(food: FoodItem, quantity: Double, unit: IngredientUnit): Double? = when (unit) {
        IngredientUnit.SERVING -> quantity
        IngredientUnit.GRAM -> food.servingSizeGrams?.takeIf { it > 0 }?.let { quantity / it }
    }

    fun nutritionFor(food: FoodItem, quantity: Double, unit: IngredientUnit): NutritionTotals? {
        val factor = factorFor(food, quantity, unit) ?: return null
        return NutritionTotals(
            calories = food.calories * factor,
            carbsG = food.carbsG * factor,
            proteinG = food.proteinG * factor,
            fatG = food.fatG * factor
        )
    }

    /**
     * The reason this ingredient line contributes nothing to the totals, or null when it does
     * contribute. [food] is null when the ingredient's food row no longer exists.
     */
    fun macroGap(food: FoodItem?, quantity: Double, unit: IngredientUnit): MacroGap? {
        if (food == null) return MacroGap.MISSING_FOOD
        if (factorFor(food, quantity, unit) == null) return MacroGap.UNRESOLVED_UNIT
        val hasMacros = food.calories != 0.0 || food.carbsG != 0.0 ||
            food.proteinG != 0.0 || food.fatG != 0.0
        return if (hasMacros) null else MacroGap.NO_MACROS
    }

    /** Sum of resolvable ingredient lines; lines with an unresolvable unit are skipped rather
     *  than failing the whole recipe. */
    fun totalFor(ingredients: List<Pair<RecipeIngredient, FoodItem>>): NutritionTotals =
        ingredients.fold(NutritionTotals.ZERO) { acc, (ingredient, food) ->
            val n = nutritionFor(food, ingredient.quantity, ingredient.unit) ?: return@fold acc
            acc + n
        }
}
