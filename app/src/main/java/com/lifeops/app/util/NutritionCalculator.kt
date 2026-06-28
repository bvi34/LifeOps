package com.lifeops.app.util

import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.RecipeIngredient

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

    /** Sum of resolvable ingredient lines; lines with an unresolvable unit are skipped rather
     *  than failing the whole recipe. */
    fun totalFor(ingredients: List<Pair<RecipeIngredient, FoodItem>>): NutritionTotals =
        ingredients.fold(NutritionTotals.ZERO) { acc, (ingredient, food) ->
            val n = nutritionFor(food, ingredient.quantity, ingredient.unit) ?: return@fold acc
            acc + n
        }
}
