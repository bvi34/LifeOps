package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.NutritionCalculator
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import java.util.UUID

private const val FREQUENT_LOOKBACK_DAYS = 30L

/** Recently-used and frequently-used foods, surfaced at the top of ad-hoc search — most real
 *  logging is the same 30-40 foods on repeat. */
class FoodLogRepository(
    private val foodLogDao: FoodLogDao,
    private val foodItemDao: FoodItemDao
) {
    /** Logs a quantity of a saved FoodItem. Macros are snapshotted at log time so a later
     *  edit to the FoodItem (or a USDA re-sync) can't rewrite diary history. */
    suspend fun logFoodItem(foodItemId: String, quantity: Double, unit: IngredientUnit): FoodLogEntry? {
        val food = foodItemDao.getById(foodItemId)?.toModel() ?: return null
        val nutrition = NutritionCalculator.nutritionFor(food, quantity, unit) ?: return null
        val entry = FoodLogEntry(
            id = UUID.randomUUID().toString(),
            foodItemId = food.id,
            name = food.name,
            quantity = quantity,
            unit = unit,
            calories = nutrition.calories,
            carbsG = nutrition.carbsG,
            proteinG = nutrition.proteinG,
            fatG = nutrition.fatG,
            loggedAt = DateUtil.now()
        )
        foodLogDao.insert(entry.toEntity())
        return entry
    }

    /** A one-off entry with no saved FoodItem behind it (e.g. typed-in macros). Can later be
     *  promoted into a permanent custom food via [promoteToCustomFood]. */
    suspend fun logAdHoc(
        name: String,
        quantity: Double,
        unit: IngredientUnit,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double
    ): FoodLogEntry {
        val entry = FoodLogEntry(
            id = UUID.randomUUID().toString(),
            foodItemId = null,
            name = name,
            quantity = quantity,
            unit = unit,
            calories = calories,
            carbsG = carbsG,
            proteinG = proteinG,
            fatG = fatG,
            loggedAt = DateUtil.now()
        )
        foodLogDao.insert(entry.toEntity())
        return entry
    }

    suspend fun getRecentFoodItems(limit: Int = 20): List<FoodItem> =
        foodLogDao.getRecentFoodItems(limit).map { it.toModel() }

    suspend fun getFrequentFoodItems(limit: Int = 20): List<FoodItem> {
        val since = DateUtil.isoFromEpoch(System.currentTimeMillis() - FREQUENT_LOOKBACK_DAYS * 24 * 60 * 60 * 1000)
        return foodLogDao.getFrequentFoodItems(since, limit).map { it.toModel() }
    }

    /** Promotes a one-off ad-hoc log entry into a permanent, searchable custom food, one tap.
     *  No-op (returns the existing food) if the entry was already tied to a saved FoodItem. */
    suspend fun promoteToCustomFood(logEntryId: String): FoodItem? {
        val entry = foodLogDao.getById(logEntryId) ?: return null
        entry.foodItemId?.let { return foodItemDao.getById(it)?.toModel() }

        val unit = IngredientUnit.from(entry.unit)
        val food = FoodItem(
            id = UUID.randomUUID().toString(),
            name = entry.name,
            servingSize = entry.quantity,
            servingUnit = if (unit == IngredientUnit.GRAM) "g" else "serving",
            servingSizeGrams = if (unit == IngredientUnit.GRAM) entry.quantity else null,
            calories = entry.calories,
            carbsG = entry.carbsG,
            proteinG = entry.proteinG,
            fatG = entry.fatG,
            source = FoodSource.Remembered,
            fdcId = null,
            createdAt = DateUtil.now()
        )
        foodItemDao.upsert(food.toEntity())
        return food
    }
}
