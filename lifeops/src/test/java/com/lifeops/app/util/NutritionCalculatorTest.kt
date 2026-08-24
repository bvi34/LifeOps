package com.lifeops.app.util

import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.RecipeIngredient
import org.junit.Assert.*
import org.junit.Test

class NutritionCalculatorTest {

    private fun food(
        servingSizeGrams: Double? = 100.0,
        calories: Double = 200.0,
        carbsG: Double = 20.0,
        proteinG: Double = 10.0,
        fatG: Double = 5.0
    ) = FoodItem(
        id = "f1", name = "Test Food", servingSize = servingSizeGrams ?: 1.0, servingUnit = "g",
        servingSizeGrams = servingSizeGrams, calories = calories, carbsG = carbsG, proteinG = proteinG,
        fatG = fatG, source = FoodSource.UsdaFoundation, createdAt = "2026-01-01T00:00:00Z"
    )

    @Test
    fun `SERVING unit scales macros by quantity directly`() {
        val nutrition = NutritionCalculator.nutritionFor(food(), quantity = 2.0, unit = IngredientUnit.SERVING)
        assertEquals(400.0, nutrition!!.calories, 0.0001)
        assertEquals(40.0, nutrition.carbsG, 0.0001)
        assertEquals(20.0, nutrition.proteinG, 0.0001)
        assertEquals(10.0, nutrition.fatG, 0.0001)
    }

    @Test
    fun `GRAM unit scales macros against the food's known serving weight`() {
        val nutrition = NutritionCalculator.nutritionFor(food(servingSizeGrams = 100.0), quantity = 50.0, unit = IngredientUnit.GRAM)
        // 50g of a 100g-serving food is half a serving.
        assertEquals(100.0, nutrition!!.calories, 0.0001)
        assertEquals(10.0, nutrition.carbsG, 0.0001)
    }

    @Test
    fun `GRAM unit is unresolvable when the food has no known serving weight`() {
        val nutrition = NutritionCalculator.nutritionFor(food(servingSizeGrams = null), quantity = 50.0, unit = IngredientUnit.GRAM)
        assertNull(nutrition)
    }

    @Test
    fun `totalFor sums resolvable ingredient lines and skips unresolvable ones`() {
        val resolvable = food(calories = 100.0, carbsG = 10.0, proteinG = 5.0, fatG = 2.0)
        val unresolvable = food(servingSizeGrams = null)
        val ingredients = listOf(
            RecipeIngredient("i1", "r1", "f1", quantity = 1.0, unit = IngredientUnit.SERVING) to resolvable,
            RecipeIngredient("i2", "r1", "f2", quantity = 1.0, unit = IngredientUnit.SERVING) to resolvable,
            RecipeIngredient("i3", "r1", "f3", quantity = 50.0, unit = IngredientUnit.GRAM) to unresolvable
        )
        val total = NutritionCalculator.totalFor(ingredients)
        assertEquals(200.0, total.calories, 0.0001)
        assertEquals(20.0, total.carbsG, 0.0001)
    }

    @Test
    fun `a line with real macros has no gap`() {
        assertNull(NutritionCalculator.macroGap(food(), quantity = 1.0, unit = IngredientUnit.SERVING))
    }

    @Test
    fun `an all-zero food reports NO_MACROS rather than counting as zero`() {
        val placeholder = food(calories = 0.0, carbsG = 0.0, proteinG = 0.0, fatG = 0.0)
        assertEquals(
            MacroGap.NO_MACROS,
            NutritionCalculator.macroGap(placeholder, quantity = 1.0, unit = IngredientUnit.SERVING)
        )
    }

    @Test
    fun `a partly-known food still counts`() {
        // Protein-only is real data, not a placeholder: only an all-zero row is a gap.
        val partial = food(calories = 0.0, carbsG = 0.0, proteinG = 6.0, fatG = 0.0)
        assertNull(NutritionCalculator.macroGap(partial, quantity = 1.0, unit = IngredientUnit.SERVING))
    }

    @Test
    fun `grams against a food with no serving weight report UNRESOLVED_UNIT`() {
        assertEquals(
            MacroGap.UNRESOLVED_UNIT,
            NutritionCalculator.macroGap(food(servingSizeGrams = null), quantity = 50.0, unit = IngredientUnit.GRAM)
        )
    }

    @Test
    fun `a deleted food reports MISSING_FOOD`() {
        assertEquals(
            MacroGap.MISSING_FOOD,
            NutritionCalculator.macroGap(null, quantity = 1.0, unit = IngredientUnit.SERVING)
        )
    }
}
