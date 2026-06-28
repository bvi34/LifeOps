package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.model.FoodSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FoodItemRepositoryTest {

    private class FakeFoodItemDao : FoodItemDao {
        val items = mutableListOf<FoodItemEntity>()
        override suspend fun getById(id: String): FoodItemEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByFdcId(fdcId: Long): FoodItemEntity? = items.firstOrNull { it.fdcId == fdcId }
        override suspend fun search(query: String, limit: Int): List<FoodItemEntity> =
            items.filter { it.name.contains(query, ignoreCase = true) || it.brand?.contains(query, ignoreCase = true) == true }
                .take(limit)
        override suspend fun upsert(item: FoodItemEntity) {
            items.removeAll { it.id == item.id }
            items += item
        }
        override suspend fun upsertAll(items: List<FoodItemEntity>) = items.forEach { upsert(it) }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
        override suspend fun countBySource(source: String): Int = items.count { it.source == source }
    }

    @Test
    fun `createCustomFood persists a Custom-sourced row with no fdcId`() = runTest {
        val dao = FakeFoodItemDao()
        val food = FoodItemRepository(dao).createCustomFood(
            name = "Protein Shake", servingSize = 1.0, servingUnit = "scoop", servingSizeGrams = 30.0,
            calories = 120.0, carbsG = 3.0, proteinG = 24.0, fatG = 1.0
        )
        assertEquals(FoodSource.Custom, food.source)
        assertNull(food.fdcId)
        assertEquals("Protein Shake", dao.items.single().name)
    }

    @Test
    fun `search is blank-guarded so an empty query never returns the whole table`() = runTest {
        val dao = FakeFoodItemDao()
        dao.items += FoodItemEntity(
            "f1", "Apple", null, 100.0, "g", 100.0, 52.0, 14.0, 0.3, 0.2, null, null,
            FoodSource.UsdaFoundation.name, 1001L, "2026-01-01T00:00:00Z"
        )
        assertTrue(FoodItemRepository(dao).search("").isEmpty())
        assertEquals(1, FoodItemRepository(dao).search("apple").size)
    }

    @Test
    fun `importUsda upserts the parsed rows and reports the count`() = runTest {
        val dao = FakeFoodItemDao()
        val foodCsv = "fdc_id,data_type,description\n1001,foundation_food,Apple"
        val nutrientCsv = "id,fdc_id,nutrient_id,amount\n1,1001,1008,52.0"
        val count = FoodItemRepository(dao).importUsda(
            foodCsv.reader().buffered(), nutrientCsv.reader().buffered()
        )
        assertEquals(1, count)
        assertEquals("usda-1001", dao.items.single().id)
    }
}
