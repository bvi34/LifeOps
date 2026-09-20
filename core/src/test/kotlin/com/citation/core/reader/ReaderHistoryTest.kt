package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coming back from a jump — the thing that makes following a reference safe to do. */
class ReaderHistoryTest {

    private val here = ReadingPlace(2, 1400)
    private val there = ReadingPlace(7, 20)

    @Test
    fun `nothing to go back to until something is remembered`() {
        val empty = ReaderHistory()
        assertTrue(empty.isEmpty)
        assertNull(empty.last)
        val (place, after) = empty.popped()
        assertNull(place)
        assertTrue(after.isEmpty)
    }

    @Test
    fun `going back lands where you were, once`() {
        val history = ReaderHistory().pushed(here)
        assertEquals(here, history.last)
        val (place, after) = history.popped()
        assertEquals(here, place)
        assertTrue("a place is somewhere to come back to, not a bookmark", after.isEmpty)
    }

    @Test
    fun `jumps nest, and unwind in the order they were taken`() {
        // A note that cites another note, a cross-reference inside an endnote.
        val history = ReaderHistory().pushed(here).pushed(there)
        val (first, rest) = history.popped()
        assertEquals(there, first)
        val (second, empty) = rest.popped()
        assertEquals(here, second)
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `the same place twice is remembered once`() {
        // Tapping the same reference again, or a second reference in the same sentence, would
        // otherwise need two taps to undo one jump.
        val history = ReaderHistory().pushed(here).pushed(here).pushed(here)
        assertEquals(1, history.places.size)
        assertTrue(history.popped().second.isEmpty)
    }

    @Test
    fun `a place returned to can be jumped from again`() {
        val history = ReaderHistory().pushed(here).popped().second.pushed(here)
        assertEquals(here, history.last)
    }

    @Test
    fun `the oldest places fall off rather than growing without bound`() {
        val deep = (1..ReaderHistory.MAX + 5).fold(ReaderHistory()) { h, i -> h.pushed(ReadingPlace(i, i)) }
        assertEquals(ReaderHistory.MAX, deep.places.size)
        assertEquals(ReadingPlace(ReaderHistory.MAX + 5, ReaderHistory.MAX + 5), deep.last)
        assertEquals("the oldest went first", ReadingPlace(6, 6), deep.places.first())
    }

    @Test
    fun `closing a book forgets where its places were`() {
        val history = ReaderHistory().pushed(here).pushed(there)
        assertTrue(history.cleared().isEmpty)
        assertFalse(history.isEmpty)
    }
}
