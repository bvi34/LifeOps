package com.logistics.app.logic

import com.logistics.app.data.model.RecipeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeNoteSummariesTest {

    private fun note(id: String, rating: Int? = null, text: String = "note") =
        RecipeNote(id = id, recipeId = "r", rating = rating, text = text, createdAt = "t", updatedAt = "t")

    @Test
    fun emptyRecipeHasNothingToSay() {
        val summary = RecipeNoteSummaries.summarize(emptyList())
        assertEquals(0, summary.count)
        assertNull(summary.averageRating)
        assertFalse(summary.hasRating)
        assertNull(RecipeNoteSummaries.label(summary))
    }

    @Test
    fun averagesOnlyTheNotesThatCarryAVerdict() {
        val summary = RecipeNoteSummaries.summarize(
            listOf(note("a", rating = 5), note("b"), note("c", rating = 4))
        )
        assertEquals(3, summary.count)
        assertEquals(2, summary.ratedCount)
        assertEquals(4.5, summary.averageRating!!, 0.0001)
    }

    @Test
    fun unratedNotesDoNotAverageInAsZero() {
        // Three notes, one five-star verdict: five stars, not 1.67.
        val summary = RecipeNoteSummaries.summarize(listOf(note("a", rating = 5), note("b"), note("c")))
        assertEquals(5.0, summary.averageRating!!, 0.0001)
        assertTrue(summary.hasRating)
    }

    @Test
    fun notesWithNoRatingAtAllReadAsCountOnly() {
        val summary = RecipeNoteSummaries.summarize(listOf(note("a"), note("b")))
        assertNull(summary.averageRating)
        assertEquals("2 notes", RecipeNoteSummaries.label(summary))
    }

    @Test
    fun ignoresRatingsOutsideTheScale() {
        // A stray value from an old row shouldn't drag the average somewhere impossible.
        val summary = RecipeNoteSummaries.summarize(listOf(note("a", rating = 4), note("b", rating = 9)))
        assertEquals(1, summary.ratedCount)
        assertEquals(4.0, summary.averageRating!!, 0.0001)
    }

    @Test
    fun formatsWholeStarsWithoutADecimal() {
        assertEquals("4", RecipeNoteSummaries.formatRating(4.0))
        assertEquals("4.5", RecipeNoteSummaries.formatRating(4.5))
        assertEquals("4.3", RecipeNoteSummaries.formatRating(4.333333))
    }

    @Test
    fun labelsARatedRecipeWithBothHalves() {
        val summary = RecipeNoteSummaries.summarize(listOf(note("a", rating = 5)))
        assertEquals("★ 5 · 1 note", RecipeNoteSummaries.label(summary))
    }
}
