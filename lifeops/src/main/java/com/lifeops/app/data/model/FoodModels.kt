package com.lifeops.app.data.model



/**
 * Food: what is in the house, what can be made from it, what was eaten, and what it was
 * nutritionally worth.
 */

enum class FoodSource {
    UsdaFoundation, UsdaBranded, Custom, Remembered;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: Custom
    }
}

data class FoodItem(
    val id: String,
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val servingSizeGrams: Double?,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
    val source: FoodSource = FoodSource.Custom,
    val fdcId: Long? = null,
    val createdAt: String
)

// Strict gram/serving math for now — see NutritionCalculator. Friendlier units (cups, tbsp)
// are a later step once strict entry has proven itself.
enum class IngredientUnit {
    GRAM, SERVING;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: SERVING
    }
}

data class Recipe(
    val id: String,
    val name: String,
    val servings: Double = 1.0,
    val createdAt: String,
    /** The method, as free text (one step per line). Null when the recipe is ingredients-only —
     *  an old row, or an import from a page that published no steps. */
    val instructions: String? = null,
    /** Where the recipe came from, when it was imported from a link. Null for hand-entered ones. */
    val sourceUrl: String? = null
)

data class RecipeIngredient(
    val id: String,
    val recipeId: String,
    val foodItemId: String,
    val quantity: Double,
    val unit: IngredientUnit,
    val sortOrder: Int = 0
)

data class NutritionTotals(
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double
) {
    operator fun plus(other: NutritionTotals) = NutritionTotals(
        calories + other.calories,
        carbsG + other.carbsG,
        proteinG + other.proteinG,
        fatG + other.fatG
    )

    operator fun div(divisor: Double) = NutritionTotals(
        calories / divisor,
        carbsG / divisor,
        proteinG / divisor,
        fatG / divisor
    )

    operator fun times(factor: Double) = NutritionTotals(
        calories * factor,
        carbsG * factor,
        proteinG * factor,
        fatG * factor
    )

    companion object {
        val ZERO = NutritionTotals(0.0, 0.0, 0.0, 0.0)
    }
}

data class RecipeNutrition(
    val recipe: Recipe,
    val total: NutritionTotals,
    val perServing: NutritionTotals
)

// How a FoodLogEntry came to exist — distinct from FoodSource (provenance of a FoodItem).
enum class FoodLogSource {
    PLANNED, ADJUSTED, AD_HOC;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: AD_HOC
    }
}

data class FoodLogEntry(
    val id: String,
    val foodItemId: String?,
    val name: String,
    val quantity: Double,
    val unit: IngredientUnit,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val loggedAt: String,
    val source: FoodLogSource = FoodLogSource.AD_HOC,
    val confirmed: Boolean = false,
    val confirmedAt: String? = null,
    val weeklyMenuItemId: String? = null
)

data class WeeklyMenuItem(
    val id: String,
    val weekStartDate: String,
    val recipeId: String? = null,
    val mealName: String,
    val plannedServings: Double = 1.0,
    val assignedDate: String? = null,
    val mealType: String? = null,
    val createdAt: String
)
