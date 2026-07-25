package com.lifeops.app.connection.service

import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.repository.FoodItemRepository
import com.lifeops.app.data.repository.FoodLogRepository

/**
 * Use-case layer for the food database (custom foods) and the diary (log entries). Wraps the two
 * repositories so the connection layer sees one nutrition surface.
 */
class FoodService(
    private val foodItemRepository: FoodItemRepository,
    private val foodLogRepository: FoodLogRepository
) {

    data class CustomFoodInput(
        val name: String,
        val brand: String? = null,
        val servingSize: Double,
        val servingUnit: String,
        val servingSizeGrams: Double? = null,
        val calories: Double,
        val carbsG: Double,
        val proteinG: Double,
        val fatG: Double,
        val fiberG: Double? = null,
        val sodiumMg: Double? = null
    )

    suspend fun createCustomFood(input: CustomFoodInput): FoodItem {
        require(input.name.isNotBlank()) { "Food name must not be blank" }
        return foodItemRepository.createCustomFood(
            name = input.name.trim(),
            brand = input.brand,
            servingSize = input.servingSize,
            servingUnit = input.servingUnit,
            servingSizeGrams = input.servingSizeGrams,
            calories = input.calories,
            carbsG = input.carbsG,
            proteinG = input.proteinG,
            fatG = input.fatG,
            fiberG = input.fiberG,
            sodiumMg = input.sodiumMg
        )
    }

    /** Log a quantity of a saved food. Null if [foodItemId] is unknown or the unit can't apply. */
    suspend fun logFood(foodItemId: String, quantity: Double, unit: IngredientUnit): FoodLogEntry? =
        foodLogRepository.logFoodItem(foodItemId, quantity, unit)

    /** Log a one-off entry with typed-in macros. */
    suspend fun logAdHoc(
        name: String,
        quantity: Double,
        unit: IngredientUnit,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double
    ): FoodLogEntry {
        require(name.isNotBlank()) { "Food name must not be blank" }
        return foodLogRepository.logAdHoc(name.trim(), quantity, unit, calories, carbsG, proteinG, fatG)
    }

    suspend fun confirmEntry(entryId: String): FoodLogEntry? = foodLogRepository.confirmEntry(entryId)

    suspend fun adjustEntry(
        entryId: String,
        quantity: Double,
        unit: IngredientUnit,
        newFoodItemId: String? = null
    ): FoodLogEntry? = foodLogRepository.adjustEntry(entryId, quantity, unit, newFoodItemId)

    suspend fun promoteToCustomFood(logEntryId: String): FoodItem? =
        foodLogRepository.promoteToCustomFood(logEntryId)
}
