package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.UsdaImporter
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import java.io.BufferedReader
import java.util.UUID

class FoodItemRepository(private val foodItemDao: FoodItemDao) {

    suspend fun search(query: String, limit: Int = 50): List<FoodItem> =
        if (query.isBlank()) emptyList() else foodItemDao.search(query.trim(), limit).map { it.toModel() }

    suspend fun getById(id: String): FoodItem? = foodItemDao.getById(id)?.toModel()

    /** The escape hatch for anything not in USDA. A custom food is a permanent row — enter
     *  "my protein shake" once, find it forever. */
    suspend fun createCustomFood(
        name: String,
        brand: String? = null,
        servingSize: Double,
        servingUnit: String,
        servingSizeGrams: Double? = null,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double,
        fiberG: Double? = null,
        sodiumMg: Double? = null
    ): FoodItem {
        val item = FoodItem(
            id = UUID.randomUUID().toString(),
            name = name,
            brand = brand,
            servingSize = servingSize,
            servingUnit = servingUnit,
            servingSizeGrams = servingSizeGrams,
            calories = calories,
            carbsG = carbsG,
            proteinG = proteinG,
            fatG = fatG,
            fiberG = fiberG,
            sodiumMg = sodiumMg,
            source = FoodSource.Custom,
            fdcId = null,
            createdAt = DateUtil.now()
        )
        foodItemDao.upsert(item.toEntity())
        return item
    }

    /** Seeds/re-syncs from the USDA FoodData Central bulk CSV download. Re-runnable: ids are
     *  derived from fdc_id, so importing the same CSVs again replaces rows rather than
     *  duplicating them. */
    suspend fun importUsda(
        foodCsv: BufferedReader,
        foodNutrientCsv: BufferedReader,
        foodPortionCsv: BufferedReader? = null,
        brandedFoodCsv: BufferedReader? = null,
        includeBranded: Boolean = false
    ): Int {
        val items = UsdaImporter.import(foodCsv, foodNutrientCsv, foodPortionCsv, brandedFoodCsv, includeBranded)
        foodItemDao.upsertAll(items.map { it.toEntity() })
        return items.size
    }
}
