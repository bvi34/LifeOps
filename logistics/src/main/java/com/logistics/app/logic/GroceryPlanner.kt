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
     * that's simply run out with no threshold — buy one. [alreadyOnList] is how much of that line is
     * already sitting on the grocery list (e.g. from a previous "restock low" sweep); it's netted out
     * so repeat sweeps top the list up to the target instead of piling more on top of it each time.
     * Never negative.
     */
    fun restockQuantity(onHand: Double, threshold: Double?, alreadyOnList: Double = 0.0): Double {
        val target = if (threshold == null) {
            1.0
        } else {
            val deficit = threshold - onHand
            if (deficit <= 0.0) 1.0 else ceil(deficit).coerceAtLeast(1.0)
        }
        return (target - alreadyOnList).coerceAtLeast(0.0)
    }

    /**
     * Which of a recipe's foods aren't already covered — by the pantry (matched by catalog id first,
     * then by case-insensitive name, so a hand-added shelf line still counts as "have it") or by
     * [alreadyOnListNames] (so re-running "from recipe" doesn't pile more of the same line onto a
     * grocery list that already has it). The result is de-duplicated (a recipe listing the same food
     * twice yields one grocery line).
     */
    fun missingIngredients(
        ingredientFoods: List<NeededFood>,
        stockedFoodIds: Set<String>,
        stockedNames: Set<String>,
        alreadyOnListNames: Set<String> = emptySet()
    ): List<NeededFood> {
        val stockedLower = stockedNames.mapTo(HashSet()) { it.lowercase() }
        val onListLower = alreadyOnListNames.mapTo(HashSet()) { it.lowercase() }
        return ingredientFoods
            .filterNot { food ->
                (food.foodItemId != null && food.foodItemId in stockedFoodIds) ||
                    food.name.lowercase() in stockedLower ||
                    food.name.lowercase() in onListLower
            }
            .distinctBy { it.foodItemId ?: it.name.lowercase() }
    }
}
