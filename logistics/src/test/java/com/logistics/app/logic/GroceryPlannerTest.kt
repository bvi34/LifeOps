package com.logistics.app.logic

import com.logistics.app.logic.GroceryPlanner.NeededFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroceryPlannerTest {

    // --- restockQuantity ---

    @Test
    fun restockRoundsDeficitUpToWholeUnits() {
        // 0.5 on hand, want 3 → buy 3 (ceil of 2.5).
        assertEquals(3.0, GroceryPlanner.restockQuantity(onHand = 0.5, threshold = 3.0), 0.0)
    }

    @Test
    fun restockIsAtLeastOneEvenAtThreshold() {
        // Exactly at the alert level still landed on the list, so buy at least one.
        assertEquals(1.0, GroceryPlanner.restockQuantity(onHand = 2.0, threshold = 2.0), 0.0)
    }

    @Test
    fun restockWithoutThresholdBuysOne() {
        assertEquals(1.0, GroceryPlanner.restockQuantity(onHand = 0.0, threshold = null), 0.0)
    }

    @Test
    fun restockNetsOutWhatsAlreadyOnTheList() {
        // 0.5 on hand, want 3 → deficit 3, but 2 are already on the list, so only top up by 1.
        assertEquals(
            1.0,
            GroceryPlanner.restockQuantity(onHand = 0.5, threshold = 3.0, alreadyOnList = 2.0),
            0.0
        )
    }

    @Test
    fun restockAddsNothingMoreOnceTheListAlreadyMeetsTarget() {
        // Re-running the sweep without buying anything shouldn't double what's on the list.
        assertEquals(
            0.0,
            GroceryPlanner.restockQuantity(onHand = 0.5, threshold = 3.0, alreadyOnList = 3.0),
            0.0
        )
        assertEquals(
            0.0,
            GroceryPlanner.restockQuantity(onHand = 0.0, threshold = null, alreadyOnList = 1.0),
            0.0
        )
    }

    // --- missingIngredients ---

    @Test
    fun keepsOnlyFoodsNotAlreadyStocked() {
        val needed = listOf(
            NeededFood("f1", "Flour"),
            NeededFood("f2", "Sugar"),
            NeededFood("f3", "Eggs")
        )
        val missing = GroceryPlanner.missingIngredients(
            ingredientFoods = needed,
            stockedFoodIds = setOf("f1"),          // have flour by id
            stockedNames = setOf("eggs")            // have eggs by name
        )
        assertEquals(listOf(NeededFood("f2", "Sugar")), missing)
    }

    @Test
    fun matchesStockedNamesCaseInsensitively() {
        val missing = GroceryPlanner.missingIngredients(
            ingredientFoods = listOf(NeededFood(null, "Olive Oil")),
            stockedFoodIds = emptySet(),
            stockedNames = setOf("OLIVE OIL")
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun deduplicatesRepeatedFoods() {
        val needed = listOf(
            NeededFood("f1", "Butter"),
            NeededFood("f1", "Butter"),
            NeededFood(null, "salt"),
            NeededFood(null, "Salt")
        )
        val missing = GroceryPlanner.missingIngredients(needed, emptySet(), emptySet())
        assertEquals(2, missing.size)
    }

    @Test
    fun skipsFoodsAlreadyOnTheGroceryList() {
        // Re-running "from recipe" shouldn't pile a second "Flour" line onto the list.
        val missing = GroceryPlanner.missingIngredients(
            ingredientFoods = listOf(NeededFood("f1", "Flour"), NeededFood("f2", "Sugar")),
            stockedFoodIds = emptySet(),
            stockedNames = emptySet(),
            alreadyOnListNames = setOf("flour")
        )
        assertEquals(listOf(NeededFood("f2", "Sugar")), missing)
    }
}
