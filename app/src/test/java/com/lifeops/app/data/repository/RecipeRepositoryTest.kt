package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.RecipeDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.RecipeEntity
import com.lifeops.app.data.db.entities.RecipeIngredientEntity
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecipeRepositoryTest {

    private class FakeRecipeDao : RecipeDao {
        val recipes = mutableListOf<RecipeEntity>()
        val ingredients = mutableListOf<RecipeIngredientEntity>()
        override fun observeAll(): Flow<List<RecipeEntity>> = flowOf(recipes)
        override suspend fun getById(id: String): RecipeEntity? = recipes.firstOrNull { it.id == id }
        override suspend fun upsert(recipe: RecipeEntity) { recipes.removeAll { it.id == recipe.id }; recipes += recipe }
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
        override suspend fun getByFdcId(fdcId: Long): FoodItemEntity? = items.firstOrNull { it.fdcId == fdcId }
        override suspend fun search(query: String, limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun upsert(item: FoodItemEntity) { items.removeAll { it.id == item.id }; items += item }
        override suspend fun upsertAll(items: List<FoodItemEntity>) = items.forEach { upsert(it) }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
        override suspend fun countBySource(source: String): Int = 0
        override suspend fun count(): Int = items.size
    }

    private fun food(id: String, calories: Double, carbsG: Double, proteinG: Double, fatG: Double) = FoodItemEntity(
        id, "Food $id", null, 100.0, "g", 100.0, calories, carbsG, proteinG, fatG, null, null,
        FoodSource.UsdaFoundation.name, null, "2026-01-01T00:00:00Z"
    )

    @Test
    fun `recipe nutrition sums ingredients times their quantities`() = runTest {
        val recipeDao = FakeRecipeDao()
        val foodDao = FakeFoodItemDao()
        foodDao.items += food("f1", calories = 100.0, carbsG = 10.0, proteinG = 5.0, fatG = 2.0)
        foodDao.items += food("f2", calories = 200.0, carbsG = 20.0, proteinG = 10.0, fatG = 4.0)

        val repo = RecipeRepository(recipeDao, foodDao)
        val recipe = repo.createRecipe("Stew", servings = 2.0)
        repo.addIngredient(recipe.id, "f1", quantity = 2.0, unit = IngredientUnit.SERVING) // 200 kcal
        repo.addIngredient(recipe.id, "f2", quantity = 1.0, unit = IngredientUnit.SERVING) // 200 kcal

        val nutrition = repo.getNutrition(recipe.id)!!
        assertEquals(400.0, nutrition.total.calories, 0.0001)
        assertEquals(200.0, nutrition.perServing.calories, 0.0001) // 400 / 2 servings
    }

    @Test
    fun `addIngredient appends to the end of the existing ingredient order`() = runTest {
        val recipeDao = FakeRecipeDao()
        val foodDao = FakeFoodItemDao()
        foodDao.items += food("f1", 100.0, 10.0, 5.0, 2.0)
        val repo = RecipeRepository(recipeDao, foodDao)
        val recipe = repo.createRecipe("Stew")

        repo.addIngredient(recipe.id, "f1", 1.0, IngredientUnit.SERVING)
        val second = repo.addIngredient(recipe.id, "f1", 1.0, IngredientUnit.SERVING)
        assertEquals(1, second.sortOrder)
    }

    @Test
    fun `getNutrition returns null for an unknown recipe`() = runTest {
        val repo = RecipeRepository(FakeRecipeDao(), FakeFoodItemDao())
        assertNull(repo.getNutrition("missing"))
    }
}
