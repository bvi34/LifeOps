package com.logistics.app.data.repository

import com.logistics.app.data.db.dao.RecipeNoteDao
import com.logistics.app.data.db.entities.RecipeNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory stand-in for Room, ordered the way the real queries are (newest first). */
private class FakeRecipeNoteDao : RecipeNoteDao {
    val rows = MutableStateFlow<List<RecipeNoteEntity>>(emptyList())

    private fun ordered(list: List<RecipeNoteEntity>) = list.sortedByDescending { it.createdAt }

    override fun observeAll(): Flow<List<RecipeNoteEntity>> = rows.map { ordered(it) }

    override fun observeForRecipe(recipeId: String): Flow<List<RecipeNoteEntity>> =
        rows.map { list -> ordered(list.filter { it.recipeId == recipeId }) }

    override suspend fun getForRecipe(recipeId: String): List<RecipeNoteEntity> =
        ordered(rows.value.filter { it.recipeId == recipeId })

    override suspend fun getById(id: String): RecipeNoteEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsert(note: RecipeNoteEntity) {
        rows.value = rows.value.filterNot { it.id == note.id } + note
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteForRecipe(recipeId: String) {
        rows.value = rows.value.filterNot { it.recipeId == recipeId }
    }
}

class RecipeNoteRepositoryTest {

    private val dao = FakeRecipeNoteDao()
    private val repo = RecipeNoteRepository(dao)

    @Test
    fun keepsANoteAgainstARecipe() = runTest {
        val note = repo.add("recipe-1", "  Halve the salt  ", rating = 4)
        assertNotNull(note)
        assertEquals("Halve the salt", note!!.text)
        assertEquals(4, note.rating)
        assertEquals(listOf(note.id), repo.getForRecipe("recipe-1").map { it.id })
    }

    @Test
    fun refusesANoteThatSaysNothing() = runTest {
        assertNull(repo.add("recipe-1", "   ", rating = null))
        assertTrue(repo.getForRecipe("recipe-1").isEmpty())
    }

    @Test
    fun keepsAVerdictWithNoWords() = runTest {
        // Stars alone are a review; a rating shouldn't need a paragraph to be worth keeping.
        val note = repo.add("recipe-1", "", rating = 5)
        assertNotNull(note)
        assertEquals("", note!!.text)
        assertEquals(5, note.rating)
    }

    @Test
    fun clampsARatingIntoTheScaleAndTreatsZeroAsNoVerdict() = runTest {
        assertEquals(5, repo.add("recipe-1", "great", rating = 9)!!.rating)
        assertNull(repo.add("recipe-1", "fine", rating = 0)!!.rating)
    }

    @Test
    fun rewritesANoteWithoutMovingItInTheLog() = runTest {
        val note = repo.add("recipe-1", "Too salty", rating = 2)!!
        val updated = repo.update(note.id, "Salt was my fault, not the recipe's", rating = 5)!!
        assertEquals(note.id, updated.id)
        assertEquals(note.createdAt, updated.createdAt)
        assertEquals(5, updated.rating)
        assertEquals(1, repo.getForRecipe("recipe-1").size)
    }

    @Test
    fun refusesAnEditThatEmptiesANoteOut() = runTest {
        val note = repo.add("recipe-1", "Too salty", rating = 2)!!
        assertNull(repo.update(note.id, "  ", rating = null))
        // The note stands rather than being silently deleted by an edit.
        assertEquals("Too salty", repo.getForRecipe("recipe-1").single().text)
    }

    @Test
    fun updatingAMissingNoteIsANoOp() = runTest {
        assertNull(repo.update("gone", "anything", rating = 3))
    }

    @Test
    fun dropsOneNoteAndLeavesTheRest() = runTest {
        val first = repo.add("recipe-1", "one")!!
        repo.add("recipe-1", "two")
        repo.remove(first.id)
        assertEquals(listOf("two"), repo.getForRecipe("recipe-1").map { it.text })
    }

    @Test
    fun sweepsEveryNoteOfARecipeWithoutTouchingAnother() = runTest {
        repo.add("recipe-1", "one")
        repo.add("recipe-2", "two")
        repo.removeForRecipe("recipe-1")
        assertTrue(repo.getForRecipe("recipe-1").isEmpty())
        assertEquals(1, repo.getForRecipe("recipe-2").size)
    }
}
