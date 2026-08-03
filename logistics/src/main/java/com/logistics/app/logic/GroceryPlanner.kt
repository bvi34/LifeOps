package com.logistics.app.logic

import kotlin.math.ceil

/**
 * Framework-free rules for building a grocery list out of the pantry and recipes. Kept pure so the
 * two decisions that actually need judgement — "how many to buy" and "what's missing" — are
 * JVM-tested and read identically wherever they're used.
 */
object GroceryPlanner {

    /** A catalog food a recipe calls for. [foodItemId] is null only for lines with no catalog link. */
    data class NeededFood(val foodItemId: String?, val name: String)

    /**
     * Suggested count to add to the list to bring a low pantry line back up to its alert level. If a
     * threshold is set, buy enough whole units to clear the deficit (rounded up); otherwise — a line
     * that's simply run out with no threshold — buy one. Never less than one, since it landed on the
     * list precisely because it needs restocking.
     */
    fun restockQuantity(onHand: Double, threshold: Double?): Double {
        if (threshold == null) return 1.0
        val deficit = threshold - onHand
        return if (deficit <= 0.0) 1.0 else ceil(deficit).coerceAtLeast(1.0)
    }

    /**
     * Which of a recipe's foods aren't already covered by the pantry — matched by catalog id first,
     * then by (case-insensitive) name, so a hand-added shelf line still counts as "have it". The
     * result is de-duplicated (a recipe listing the same food twice yields one grocery line).
     */
    fun missingIngredients(
        ingredientFoods: List<NeededFood>,
        stockedFoodIds: Set<String>,
        stockedNames: Set<String>
    ): List<NeededFood> {
        val stockedLower = stockedNames.mapTo(HashSet()) { it.lowercase() }
        return ingredientFoods
            .filterNot { food ->
                (food.foodItemId != null && food.foodItemId in stockedFoodIds) ||
                    food.name.lowercase() in stockedLower
            }
            .distinctBy { it.foodItemId ?: it.name.lowercase() }
    }
}
