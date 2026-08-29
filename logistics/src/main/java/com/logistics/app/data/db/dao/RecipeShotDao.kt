package com.logistics.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.logistics.app.data.db.entities.RecipeShotEntity
import kotlinx.coroutines.flow.Flow

/** The screenshots kept with recipes. Ordered by [RecipeShotEntity.sortOrder] so a multi-page
 *  recipe reads in the order it was shot. */
@Dao
interface RecipeShotDao {

    @Query("SELECT * FROM recipe_shots ORDER BY recipeId, sortOrder, createdAt")
    fun observeAll(): Flow<List<RecipeShotEntity>>

    @Query("SELECT * FROM recipe_shots WHERE recipeId = :recipeId ORDER BY sortOrder, createdAt")
    fun observeForRecipe(recipeId: String): Flow<List<RecipeShotEntity>>

    @Query("SELECT * FROM recipe_shots WHERE recipeId = :recipeId ORDER BY sortOrder, createdAt")
    suspend fun getForRecipe(recipeId: String): List<RecipeShotEntity>

    @Query("SELECT * FROM recipe_shots WHERE id = :id")
    suspend fun getById(id: String): RecipeShotEntity?

    @Query("SELECT * FROM recipe_shots")
    suspend fun getAll(): List<RecipeShotEntity>

    @Upsert
    suspend fun upsert(shot: RecipeShotEntity)

    @Query("DELETE FROM recipe_shots WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recipe_shots WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: String)
}
