package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.Key
import com.utilities.app.keyboard.logic.KeyAction
import com.utilities.app.keyboard.logic.KeyEffect
import com.utilities.app.keyboard.logic.KeyboardMachine
import com.utilities.app.keyboard.logic.KeyboardState
import com.utilities.app.keyboard.logic.Layer
import com.utilities.app.keyboard.logic.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a keyboard is judged by, none of which need a phone.
 *
 * Every assertion here is something somebody would notice within a minute of typing on it: shift
 * that sticks when it should not, a symbols page you get thrown out of, a sentence that starts
 * lowercase. A keyboard whose behaviour lives in a touch handler can only be checked by typing on
 * it; this one can be checked here.
 */
class KeyboardMachineTest {

    private val a = Key(output = "a", alternate = "á")
    private val shift = Key(label = "⇧", action = KeyAction.SHIFT)
    private val space = Key(label = "space", action = KeyAction.SPACE)
    private val enter = Key(label = "return", action = KeyAction.ENTER)
    private val backspace = Key(label = "⌫", action = KeyAction.BACKSPACE, repeats = true)
    private val toSymbols = Key(label = "?123", action = KeyAction.TO_SYMBOLS)

    @Test
    fun `a letter types itself, shifted or not`() {
        val (_, lower) = KeyboardMachine.press(KeyboardState(), a)
        assertEquals(KeyEffect.Type("a"), lower)

        val (_, upper) = KeyboardMachine.press(KeyboardState(shift = ShiftState.ON), a)
        assertEquals(KeyEffect.Type("A"), upper)
    }

    @Test
    fun `a held shift is spent by one letter and a locked one is not`() {
        val (afterHeld, _) = KeyboardMachine.press(KeyboardState(shift = ShiftState.ON), a)
        assertEquals(ShiftState.OFF, afterHeld.shift)

        val (afterLocked, effect) = KeyboardMachine.press(KeyboardState(shift = ShiftState.LOCKED), a)
        assertEquals(ShiftState.LOCKED, afterLocked.shift)
        assertEquals(KeyEffect.Type("A"), effect)
    }

    @Test
    fun `tapping shift twice quickly locks it, and tapping a locked shift turns it off`() {
        var state = KeyboardState()
        state = KeyboardMachine.press(state, shift, nowMs = 1_000L).first
        assertEquals(ShiftState.ON, state.shift)

        state = KeyboardMachine.press(state, shift, lastShiftTapMs = 1_000L, nowMs = 1_200L).first
        assertEquals("a double tap locks", ShiftState.LOCKED, state.shift)

        state = KeyboardMachine.press(state, shift, lastShiftTapMs = 1_200L, nowMs = 1_300L).first
        assertEquals("a locked shift goes all the way off", ShiftState.OFF, state.shift)
    }

    @Test
    fun `two slow taps are two taps, not a lock`() {
        var state = KeyboardMachine.press(KeyboardState(), shift, nowMs = 1_000L).first
        state = KeyboardMachine.press(state, shift, lastShiftTapMs = 1_000L, nowMs = 5_000L).first
        assertEquals(ShiftState.OFF, state.shift)
    }

    @Test
    fun `a long press types the alternate, unshifted, and spends the shift`() {
        // Somebody holding `a` for `á` with shift on wants á, not Á.
        val (state, effect) = KeyboardMachine.press(
            KeyboardState(shift = ShiftState.ON),
            a,
            longPress = true
        )
        assertEquals(KeyEffect.Type("á"), effect)
        assertEquals(ShiftState.OFF, state.shift)
    }

    @Test
    fun `a long press on a key with no alternate types the key`() {
        val plain = Key(output = "d")
        val (_, effect) = KeyboardMachine.press(KeyboardState(), plain, longPress = true)
        assertEquals(KeyEffect.Type("d"), effect)
    }

    @Test
    fun `the symbols page is not left after one character`() {
        // A deliberate disagreement with several keyboards: somebody who went to the symbols page
        // for a pound sign very often wants £30.00, and being thrown back costs two taps each time.
        val symbols = KeyboardState(layer = Layer.SYMBOLS)
        val (after, _) = KeyboardMachine.press(symbols, Key(output = "£"))
        assertEquals(Layer.SYMBOLS, after.layer)
    }

    @Test
    fun `the layer keys move between pages and ask the editor for nothing`() {
        val (after, effect) = KeyboardMachine.press(KeyboardState(), toSymbols)
        assertEquals(Layer.SYMBOLS, after.layer)
        assertEquals(KeyEffect.None, effect)
    }

    @Test
    fun `space types a space and a long press on it hands the keyboard back`() {
        assertEquals(KeyEffect.Type(" "), KeyboardMachine.press(KeyboardState(), space).second)
        assertEquals(
            KeyEffect.SwitchKeyboard,
            KeyboardMachine.press(KeyboardState(), space, longPress = true).second
        )
    }

    @Test
    fun `backspace and enter say what they are`() {
        assertEquals(KeyEffect.Delete, KeyboardMachine.press(KeyboardState(), backspace).second)
        assertEquals(KeyEffect.Commit, KeyboardMachine.press(KeyboardState(), enter).second)
    }

    @Test
    fun `a sentence starts with a capital`() {
        assertTrue(KeyboardMachine.startsASentence(""))
        assertTrue(KeyboardMachine.startsASentence("   "))
        assertTrue(KeyboardMachine.startsASentence("Hello there. "))
        assertTrue(KeyboardMachine.startsASentence("Really? "))
        assertTrue(KeyboardMachine.startsASentence("Stop! "))
        assertTrue(KeyboardMachine.startsASentence("A line\n"))
    }

    @Test
    fun `mid-sentence and mid-word do not`() {
        assertFalse(KeyboardMachine.startsASentence("Hello the"))
        assertFalse(KeyboardMachine.startsASentence("Hello there "))
        // The cursor sitting right after the stop is somebody still deciding; capitalising nothing
        // yet is free, and the space is what says the next sentence has begun.
        assertFalse(KeyboardMachine.startsASentence("Hello there."))
    }

    @Test
    fun `auto-shift follows the cursor and leaves a locked shift alone`() {
        assertEquals(
            ShiftState.ON,
            KeyboardMachine.autoShift(KeyboardState(), "Done. ").shift
        )
        assertEquals(
            ShiftState.OFF,
            KeyboardMachine.autoShift(KeyboardState(shift = ShiftState.ON), "midword").shift
        )
        assertEquals(
            "somebody typing in capitals did not ask for help",
            ShiftState.LOCKED,
            KeyboardMachine.autoShift(KeyboardState(shift = ShiftState.LOCKED), "midword").shift
        )
    }

    @Test
    fun `auto-shift does nothing when it is switched off`() {
        assertEquals(
            ShiftState.OFF,
            KeyboardMachine.autoShift(KeyboardState(), "Done. ", enabled = false).shift
        )
    }
}
