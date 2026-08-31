package com.logistics.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.logistics.app.data.db.entities.RecipeNoteEntity
import kotlinx.coroutines.flow.Flow

/** The notes and reviews kept against recipes. Newest first: a cooking note is read as a log, and
 *  the most recent time you made something is the one that matters. */
@Dao
interface RecipeNoteDao {

    @Query("SELECT * FROM recipe_notes ORDER BY recipeId, createdAt DESC")
    fun observeAll(): Flow<List<RecipeNoteEntity>>

    @Query("SELECT * FROM recipe_notes WHERE recipeId = :recipeId ORDER BY createdAt DESC")
    fun observeForRecipe(recipeId: String): Flow<List<RecipeNoteEntity>>

    @Query("SELECT * FROM recipe_notes WHERE recipeId = :recipeId ORDER BY createdAt DESC")
    suspend fun getForRecipe(recipeId: String): List<RecipeNoteEntity>

    @Query("SELECT * FROM recipe_notes WHERE id = :id")
    suspend fun getById(id: String): RecipeNoteEntity?

    @Upsert
    suspend fun upsert(note: RecipeNoteEntity)

    @Query("DELETE FROM recipe_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recipe_notes WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: String)
}
