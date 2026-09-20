package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.KeyLayouts
import com.utilities.app.keyboard.logic.KeyNeighbours
import com.utilities.app.keyboard.logic.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which keys touch, worked out from the layout.
 *
 * Worth a test of its own because it is geometry rather than a table: the letters' positions come
 * out of the same widths the canvas draws with, so this is also the test that notices if somebody
 * changes a row and quietly moves half the alphabet.
 */
class KeyNeighboursTest {

    private val qwerty = KeyNeighbours.QWERTY

    @Test
    fun `a letter is next to the letters beside it and not to the one after that`() {
        assertTrue(qwerty.adjacent('q', 'w'))
        assertTrue(qwerty.adjacent('w', 'e'))
        assertFalse(qwerty.adjacent('q', 'e'))
        assertFalse(qwerty.adjacent('q', 'p'))
    }

    @Test
    fun `the row below counts, which is the whole reason this is geometry`() {
        assertTrue("n is under h and j", qwerty.adjacent('h', 'n'))
        assertTrue("a is under q", qwerty.adjacent('q', 'a'))
        assertFalse("e is two keys over from a", qwerty.adjacent('a', 'e'))
    }

    @Test
    fun `the shift key's width is accounted for, because it moves the bottom row`() {
        // z sits under a and s rather than under the left edge, because shift takes the corner.
        assertTrue(qwerty.adjacent('a', 'z'))
        assertTrue(qwerty.adjacent('s', 'z'))
        assertFalse(qwerty.adjacent('q', 'z'))
    }

    @Test
    fun `touching is mutual`() {
        ('a'..'z').forEach { one ->
            qwerty.of(one).forEach { other ->
                assertTrue("$one touches $other but not the other way round", qwerty.adjacent(other, one))
            }
        }
    }

    @Test
    fun `a letter is not its own neighbour, and every letter has some`() {
        ('a'..'z').forEach { letter ->
            assertFalse(qwerty.adjacent(letter, letter))
            assertTrue("$letter is on an island", qwerty.of(letter).isNotEmpty())
        }
    }

    @Test
    fun `keys that do not type a letter are not on the map at all`() {
        assertEquals(emptySet<Char>(), qwerty.of(' '))
        assertEquals(emptySet<Char>(), qwerty.of('⇧'))
        assertEquals(emptySet<Char>(), qwerty.of('1'))
    }

    @Test
    fun `the numeric pad has its own geometry, and no letters in it`() {
        val pad = KeyNeighbours.of(KeyLayouts.of(Layer.NUMBERS))
        ('a'..'z').forEach { assertEquals(emptySet<Char>(), pad.of(it)) }
    }
}
