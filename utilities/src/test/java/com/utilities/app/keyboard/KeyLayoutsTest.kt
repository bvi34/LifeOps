package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.Key
import com.utilities.app.keyboard.logic.KeyAction
import com.utilities.app.keyboard.logic.KeyLayouts
import com.utilities.app.keyboard.logic.KeyboardLayout
import com.utilities.app.keyboard.logic.Layer
import com.utilities.app.keyboard.logic.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrangement, checked without drawing it.
 *
 * Nearly every bug a keyboard has that is not in its touch handling is in here — a row whose widths
 * do not add up, a layer with no way back out of it, a key that types nothing and does nothing —
 * and every one of them is decidable from the data. Which is the reason the layouts are data.
 */
class KeyLayoutsTest {

    private fun keys(layout: KeyboardLayout): List<Key> = layout.rows.flatMap { it.keys }

    private val everyPage: List<Pair<String, KeyboardLayout>> = listOf(
        "letters" to KeyLayouts.of(Layer.LETTERS),
        "letters+digits" to KeyLayouts.of(Layer.LETTERS, numberRow = true),
        "symbols" to KeyLayouts.of(Layer.SYMBOLS),
        "more" to KeyLayouts.of(Layer.MORE),
        "numbers" to KeyLayouts.of(Layer.NUMBERS)
    )

    @Test
    fun `every key either types something or does something`() {
        everyPage.forEach { (name, layout) ->
            keys(layout).forEach { key ->
                assertTrue(
                    "$name has a key that types nothing and does nothing: '${key.label}'",
                    key.output != null || key.action != null
                )
                assertTrue("$name has a key with no cap", key.cap(ShiftState.OFF).isNotEmpty())
            }
        }
    }

    @Test
    fun `every key has a positive width, so a row can always be normalised`() {
        everyPage.forEach { (name, layout) ->
            layout.rows.forEachIndexed { index, row ->
                assertTrue("$name row $index is empty", row.keys.isNotEmpty())
                assertTrue(
                    "$name row $index has a key with no width",
                    row.keys.all { it.weight > 0f }
                )
            }
        }
    }

    @Test
    fun `every page you can get into has a way out of it`() {
        // The one that matters. A symbols page with no ABC key is a keyboard somebody has to close
        // the app to escape from.
        listOf(Layer.SYMBOLS, Layer.MORE).forEach { layer ->
            val actions = keys(KeyLayouts.of(layer)).mapNotNull { it.action }.toSet()
            assertTrue(
                "$layer cannot get back to the letters",
                KeyAction.TO_LETTERS in actions || KeyAction.TO_SYMBOLS in actions
            )
        }
        assertTrue(
            "the second symbols page cannot get back to the first",
            keys(KeyLayouts.of(Layer.MORE)).any { it.action == KeyAction.TO_SYMBOLS }
        )
    }

    @Test
    fun `every page can delete, and delete repeats`() {
        everyPage.forEach { (name, layout) ->
            val backspace = keys(layout).firstOrNull { it.action == KeyAction.BACKSPACE }
            assertTrue("$name has no backspace", backspace != null)
            assertTrue("$name's backspace does not repeat", backspace!!.repeats)
        }
    }

    @Test
    fun `only the keys that should repeat do`() {
        // A repeating letter is a keyboard that types "aaaaaaa" when somebody rests their thumb.
        everyPage.forEach { (name, layout) ->
            keys(layout).filter { it.repeats }.forEach { key ->
                assertEquals("$name repeats on '${key.label}'", KeyAction.BACKSPACE, key.action)
            }
        }
    }

    @Test
    fun `the numeric pad offers no letters and no way to reach them`() {
        // An editor that asked for a phone number and got QWERTY is the single most irritating
        // thing a keyboard does. There is nothing to go back to, so there is no key for it.
        val pad = keys(KeyLayouts.of(Layer.NUMBERS))
        assertTrue(pad.none { it.output?.any { c -> c.isLetter() } == true })
        assertTrue(pad.none { it.action == KeyAction.TO_LETTERS })
        assertTrue(pad.any { it.output == "0" })
        assertTrue(pad.any { it.action == KeyAction.ENTER })
    }

    @Test
    fun `the letters page has the alphabet on it, once each`() {
        val typed = keys(KeyLayouts.of(Layer.LETTERS)).mapNotNull { it.output }.filter { it.length == 1 }
        val letters = typed.filter { it[0].isLetter() }
        assertEquals("every letter exactly once", 26, letters.size)
        assertEquals(('a'..'z').toSet(), letters.map { it[0] }.toSet())
    }

    @Test
    fun `punctuation a sentence needs is on the letters page`() {
        // Not on the symbols page only: a keyboard that makes you change layer to end a sentence is
        // a keyboard that gets uninstalled on day one.
        val typed = keys(KeyLayouts.of(Layer.LETTERS)).mapNotNull { it.output }.toSet()
        assertTrue("." in typed)
        assertTrue("," in typed)
    }

    @Test
    fun `the number row is an addition, not a replacement`() {
        val plain = KeyLayouts.of(Layer.LETTERS)
        val withDigits = KeyLayouts.of(Layer.LETTERS, numberRow = true)
        assertEquals(plain.rows.size + 1, withDigits.rows.size)
        assertEquals(
            ('0'..'9').map { it.toString() }.toSet(),
            withDigits.rows.first().keys.mapNotNull { it.output }.toSet()
        )
        // …and only on the letters. The symbols page already has a row of digits.
        assertEquals(
            KeyLayouts.of(Layer.SYMBOLS).rows.size,
            KeyLayouts.of(Layer.SYMBOLS, numberRow = true).rows.size
        )
    }

    @Test
    fun `the enter key wears the editor's own action`() {
        val search = keys(KeyLayouts.of(Layer.LETTERS, enterLabel = "search"))
            .first { it.action == KeyAction.ENTER }
        assertEquals("search", search.label)
    }

    @Test
    fun `shift changes the letters and leaves everything else alone`() {
        val letters = keys(KeyLayouts.of(Layer.LETTERS))
        val a = letters.first { it.output == "a" }
        assertEquals("A", a.cap(ShiftState.ON))
        assertEquals("A", a.typed(ShiftState.LOCKED))
        assertEquals("a", a.typed(ShiftState.OFF))

        val space = letters.first { it.action == KeyAction.SPACE }
        assertEquals("a key that does something keeps its cap", "space", space.cap(ShiftState.LOCKED))
    }

    @Test
    fun `an alternate is never the same as what the key already types`() {
        // A printed alternate that does nothing new is a promise the cap makes and the long press
        // does not keep.
        everyPage.forEach { (name, layout) ->
            keys(layout).forEach { key ->
                val alternate = key.alternate ?: return@forEach
                assertFalse("$name: '${key.label}' long-presses to itself", alternate == key.output)
                assertTrue("$name: '${key.label}' has an empty alternate", alternate.isNotEmpty())
            }
        }
    }
}
