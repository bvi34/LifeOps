package com.lifeops.app.connection.service

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.db.dao.RecipeDao
import com.lifeops.app.data.db.dao.WeeklyMenuItemDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.FoodLogEntryEntity
import com.lifeops.app.data.db.entities.RecipeEntity
import com.lifeops.app.data.db.entities.RecipeIngredientEntity
import com.lifeops.app.data.db.entities.WeeklyMenuItemEntity
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.repository.FoodLogRepository
import com.lifeops.app.data.repository.RecipeRepository
import com.lifeops.app.data.repository.WeeklyMenuRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Planning a meal writes two rows that have to stay in step: the menu item and the planned diary
 * entry it stands for. These pin the parts that are easy to get wrong — the macros are the recipe's
 * per-serving figures scaled by the portions planned, and unplanning never rewrites a day the user
 * already confirmed.
 */
class MealPlanServiceTest {

    private class FakeMenuDao : WeeklyMenuItemDao {
        val items = mutableListOf<WeeklyMenuItemEntity>()
        override fun observeByWeek(weekStartDate: String): Flow<List<WeeklyMenuItemEntity>> =
            flowOf(items.filter { it.weekStartDate == weekStartDate })
        override suspend fun getById(id: String): WeeklyMenuItemEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByAssignedDate(date: String): List<WeeklyMenuItemEntity> =
            items.filter { it.assignedDate == date }
        override suspend fun upsert(item: WeeklyMenuItemEntity) {
            items.removeAll { it.id == item.id }; items += item
        }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
    }

    private class FakeFoodLogDao : FoodLogDao {
        val entries = mutableListOf<FoodLogEntryEntity>()
        override suspend fun insert(entry: FoodLogEntryEntity) { entries += entry }
        override suspend fun update(entry: FoodLogEntryEntity) {
            entries.removeAll { it.id == entry.id }; entries += entry
        }
        override suspend fun getById(id: String): FoodLogEntryEntity? = entries.firstOrNull { it.id == id }
        override suspend fun getByWeeklyMenuItemId(weeklyMenuItemId: String): FoodLogEntryEntity? =
            entries.firstOrNull { it.weeklyMenuItemId == weeklyMenuItemId }
        override suspend fun delete(id: String) { entries.removeAll { it.id == id } }
        override suspend fun getRecent(limit: Int): List<FoodLogEntryEntity> = entries.takeLast(limit)
        override suspend fun getSince(startIso: String): List<FoodLogEntryEntity> =
            entries.filter { it.loggedAt >= startIso }
        override fun observeByDateRange(startIso: String, endIso: String): Flow<List<FoodLogEntryEntity>> =
            flowOf(entries.filter { it.loggedAt >= startIso && it.loggedAt < endIso })
        override suspend fun getRecentFoodItems(limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun getFrequentFoodItems(since: String, limit: Int): List<FoodItemEntity> = emptyList()
    }

    private class FakeRecipeDao : RecipeDao {
        val recipes = mutableListOf<RecipeEntity>()
        val ingredients = mutableListOf<RecipeIngredientEntity>()
        override fun observeAll(): Flow<List<RecipeEntity>> = flowOf(recipes)
        override suspend fun getAll(): List<RecipeEntity> = recipes.toList()
        override suspend fun getAllIngredients(): List<RecipeIngredientEntity> = ingredients.toList()
        override suspend fun getById(id: String): RecipeEntity? = recipes.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<RecipeEntity?> = flowOf(recipes.firstOrNull { it.id == id })
        override suspend fun upsert(recipe: RecipeEntity) {
            recipes.removeAll { it.id == recipe.id }; recipes += recipe
        }
        override suspend fun delete(id: String) { recipes.removeAll { it.id == id } }
        override suspend fun getIngredients(recipeId: String): List<RecipeIngredientEntity> =
            ingredients.filter { it.recipeId == recipeId }.sortedBy { it.sortOrder }
        override fun observeIngredients(recipeId: String): Flow<List<RecipeIngredientEntity>> =
            flowOf(ingredients.filter { it.recipeId == recipeId })
        override suspend fun upsertIngredient(ingredient: RecipeIngredientEntity) {
            ingredients.removeAll { it.id == ingredient.id }; ingredients += ingredient
        }
        override suspend fun deleteIngredient(id: String) { ingredients.removeAll { it.id == id } }
    }

    private class FakeFoodItemDao : FoodItemDao {
        val items = mutableListOf<FoodItemEntity>()
        override suspend fun getById(id: String): FoodItemEntity? = items.firstOrNull { it.id == id }
        override suspend fun getAll(): List<FoodItemEntity> = items.toList()
        override suspend fun getByFdcId(fdcId: Long): FoodItemEntity? = null
        override suspend fun search(query: String, limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun upsert(item: FoodItemEntity) { items.removeAll { it.id == item.id }; items += item }
        override suspend fun upsertAll(items: List<FoodItemEntity>) = items.forEach { upsert(it) }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
        override suspend fun countBySource(source: String): Int = 0
        override suspend fun count(): Int = items.size
    }

    private class Fixture {
        val menuDao = FakeMenuDao()
        val foodLogDao = FakeFoodLogDao()
        val recipeDao = FakeRecipeDao()
        val foodItemDao = FakeFoodItemDao()
        val recipeRepository = RecipeRepository(recipeDao, foodItemDao)
        val foodLogRepository = FoodLogRepository(foodLogDao, foodItemDao)
        val service = MealPlanService(WeeklyMenuRepository(menuDao), recipeRepository, foodLogRepository)

        /** A one-serving recipe of a single 200 kcal food. */
        suspend fun stew(): String {
            foodItemDao.items += FoodItemEntity(
                id = "f1", name = "Beef", brand = null, servingSize = 1.0, servingUnit = "serving",
                servingSizeGrams = 100.0, calories = 200.0, carbsG = 4.0, proteinG = 30.0, fatG = 8.0,
                fiberG = null, sodiumMg = null, source = "Custom", fdcId = null,
                createdAt = "2026-01-01T00:00:00Z"
            )
            val recipe = recipeRepository.createRecipe("Stew", servings = 1.0)
            recipeRepository.addIngredient(recipe.id, "f1", 1.0, IngredientUnit.SERVING)
            return recipe.id
        }
    }

    @Test
    fun `planning a recipe writes the menu item and a planned diary entry`() = runTest {
        val f = Fixture()
        val recipeId = f.stew()

        val item = f.service.plan(date = "2026-08-26", recipeId = recipeId, servings = 2.0, mealType = "dinner")

        assertEquals("Stew", item.mealName)
        assertEquals("2026-08-26", item.assignedDate)
        // Wednesday 26 Aug 2026 belongs to the week starting Monday the 24th.
        assertEquals("2026-08-24", item.weekStartDate)

        val entry = f.foodLogDao.entries.single()
        assertEquals(item.id, entry.weeklyMenuItemId)
        assertEquals(FoodLogSource.PLANNED.name, entry.source)
        assertFalse(entry.confirmed)
        // Two servings of a one-serving, 200 kcal recipe.
        assertEquals(400.0, entry.calories, 0.0001)
        assertEquals(60.0, entry.proteinG, 0.0001)
        assertEquals(2.0, entry.quantity, 0.0001)
    }

    @Test
    fun `a freeform meal holds a slot without inventing macros`() = runTest {
        val f = Fixture()
        val item = f.service.plan(date = "2026-08-26", mealName = "Leftovers")
        assertEquals("Leftovers", item.mealName)
        assertNull(item.recipeId)
        assertTrue(f.foodLogDao.entries.isEmpty())
    }

    @Test
    fun `a meal with neither a recipe nor a name is refused`() = runTest {
        val f = Fixture()
        try {
            f.service.plan(date = "2026-08-26")
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The point of the rule: a slot with no identity is not a plan.
        }
    }

    @Test
    fun `unplanning removes the meal and its unconfirmed entry`() = runTest {
        val f = Fixture()
        val item = f.service.plan(date = "2026-08-26", recipeId = f.stew())

        assertTrue(f.service.unplan(item.id))
        assertTrue(f.menuDao.items.isEmpty())
        assertTrue(f.foodLogDao.entries.isEmpty())
    }

    @Test
    fun `unplanning keeps an entry that was already confirmed`() = runTest {
        val f = Fixture()
        val item = f.service.plan(date = "2026-08-26", recipeId = f.stew())
        f.foodLogRepository.confirmEntry(f.foodLogDao.entries.single().id)

        assertTrue(f.service.unplan(item.id))
        assertTrue(f.menuDao.items.isEmpty())
        // What was eaten outlives the plan that suggested it.
        val kept = f.foodLogDao.entries.single()
        assertTrue(kept.confirmed)
        assertNotNull(kept.weeklyMenuItemId)
    }

    @Test
    fun `unplanning an unknown meal reports not found`() = runTest {
        assertFalse(Fixture().service.unplan("missing"))
    }
}
