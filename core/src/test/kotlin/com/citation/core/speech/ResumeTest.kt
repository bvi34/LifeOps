package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a book opens when the eye and the voice left off in different places. */
class ResumeTest {

    @Test
    fun `a book never listened to opens where it was read`() {
        val point = Resume.choose(SavedPlace(3, 900, savedAt = 10L), listening = null)
        assertEquals(3, point?.chapterOrdinal)
        assertEquals(900, point?.charOffset)
        // Not canonical: it is whatever the reading mode that saved it stores.
        assertFalse(point!!.canonical)
    }

    @Test
    fun `a book only ever listened to opens where the voice got to`() {
        val point = Resume.choose(reading = null, listening = SavedPlace(5, 120, savedAt = 10L))
        assertEquals(5, point?.chapterOrdinal)
        assertTrue(point!!.canonical)
    }

    @Test
    fun `the more recent of the two wins`() {
        val read = SavedPlace(2, 400, savedAt = 200L)
        val listened = SavedPlace(9, 50, savedAt = 100L)
        assertEquals(2, Resume.choose(read, listened)?.chapterOrdinal)
        assertEquals(9, Resume.choose(read.copy(savedAt = 50L), listened)?.chapterOrdinal)
    }

    @Test
    fun `an hour of listening in a pocket beats the page you last looked at`() {
        // The case the whole thing exists for.
        val read = SavedPlace(1, 0, savedAt = 1_000L)
        val listened = SavedPlace(4, 2_100, savedAt = 1_000L + 3_600_000L)
        val point = Resume.choose(read, listened)
        assertEquals(4, point?.chapterOrdinal)
        assertEquals(2_100, point?.charOffset)
        assertTrue(point!!.canonical)
    }

    @Test
    fun `a stamped place beats an unstamped one either way round`() {
        // Rows written before positions were stamped have no time to compare against.
        assertTrue(Resume.choose(SavedPlace(1, 10, null), SavedPlace(7, 20, 5L))!!.canonical)
        assertFalse(Resume.choose(SavedPlace(1, 10, 5L), SavedPlace(7, 20, null))!!.canonical)
    }

    @Test
    fun `a tie goes to the voice, which only got there by actually reading it`() {
        val point = Resume.choose(SavedPlace(1, 10, 500L), SavedPlace(7, 20, 500L))
        assertEquals(7, point?.chapterOrdinal)
    }

    @Test
    fun `a book with no recorded place anywhere opens at the beginning`() {
        assertNull(Resume.choose(null, null))
    }
}
