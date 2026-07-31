package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.NutritionCalculator
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

private const val FREQUENT_LOOKBACK_DAYS = 30L

/** Recently-used and frequently-used foods, surfaced at the top of ad-hoc search — most real
 *  logging is the same 30-40 foods on repeat. */
class FoodLogRepository(
    private val foodLogDao: FoodLogDao,
    private val foodItemDao: FoodItemDao
) {
    /** Reports: all diary entries logged on/after [startIso], for range-based nutrition stats. */
    suspend fun getEntriesSince(startIso: String): List<FoodLogEntry> =
        foodLogDao.getSince(startIso).map { it.toModel() }

    /** Logs a quantity of a saved FoodItem. Macros are snapshotted at log time so a later
     *  edit to the FoodItem (or a USDA re-sync) can't rewrite diary history. */
    suspend fun logFoodItem(foodItemId: String, quantity: Double, unit: IngredientUnit): FoodLogEntry? {
        val food = foodItemDao.getById(foodItemId)?.toModel() ?: return null
        val nutrition = NutritionCalculator.nutritionFor(food, quantity, unit) ?: return null
        val now = DateUtil.now()
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
            loggedAt = now,
            source = FoodLogSource.AD_HOC,
            confirmed = true,
            confirmedAt = now
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
        val now = DateUtil.now()
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
            loggedAt = now,
            source = FoodLogSource.AD_HOC,
            confirmed = true,
            confirmedAt = now
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

    /** All entries logged on the local calendar day [date] (yyyy-MM-dd), in chronological order. */
    fun observeForDate(date: String): Flow<List<FoodLogEntry>> {
        val day = LocalDate.parse(date)
        val startIso = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate(day.toString(), 0))
        val endIso = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate(day.plusDays(1).toString(), 0))
        return foodLogDao.observeByDateRange(startIso, endIso).map { entries -> entries.map { it.toModel() } }
    }

    /** One-tap Confirm for a planned entry — flips [FoodLogEntry.confirmed] and stamps the time,
     *  leaving its macros untouched since the user is accepting the plan as-is. */
    suspend fun confirmEntry(entryId: String): FoodLogEntry? {
        val entry = foodLogDao.getById(entryId)?.toModel() ?: return null
        if (entry.confirmed) return entry
        val updated = entry.copy(confirmed = true, confirmedAt = DateUtil.now())
        foodLogDao.update(updated.toEntity())
        return updated
    }

    /** Changes quantity/unit (and optionally swaps to a different saved food) on an existing
     *  entry, marking it Adjusted and confirmed. Macros are recomputed from the linked FoodItem
     *  when one is known; otherwise they're scaled proportionally from the entry's own quantity. */
    suspend fun adjustEntry(
        entryId: String,
        quantity: Double,
        unit: IngredientUnit,
        newFoodItemId: String? = null
    ): FoodLogEntry? {
        val entry = foodLogDao.getById(entryId)?.toModel() ?: return null
        val foodItemId = newFoodItemId ?: entry.foodItemId

        val updated = if (foodItemId != null) {
            val food = foodItemDao.getById(foodItemId)?.toModel() ?: return null
            val nutrition = NutritionCalculator.nutritionFor(food, quantity, unit) ?: return null
            entry.copy(
                foodItemId = food.id,
                name = food.name,
                quantity = quantity,
                unit = unit,
                calories = nutrition.calories,
                carbsG = nutrition.carbsG,
                proteinG = nutrition.proteinG,
                fatG = nutrition.fatG,
                source = FoodLogSource.ADJUSTED,
                confirmed = true,
                confirmedAt = DateUtil.now()
            )
        } else {
            val factor = if (entry.quantity != 0.0) quantity / entry.quantity else 0.0
            entry.copy(
                quantity = quantity,
                unit = unit,
                calories = entry.calories * factor,
                carbsG = entry.carbsG * factor,
                proteinG = entry.proteinG * factor,
                fatG = entry.fatG * factor,
                source = FoodLogSource.ADJUSTED,
                confirmed = true,
                confirmedAt = DateUtil.now()
            )
        }
        foodLogDao.update(updated.toEntity())
        return updated
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
