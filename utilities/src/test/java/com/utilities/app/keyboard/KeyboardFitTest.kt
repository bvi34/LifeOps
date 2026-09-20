package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.KeyboardFit
import com.utilities.app.keyboard.logic.KeyboardFit.Edges
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the keys stop, checked without a phone.
 *
 * The bug this is here for is the one somebody reports as "the keyboard is too low": the last row
 * drawn under the gesture handle, with the system's keyboard-switch button sitting on top of the
 * enter key. It is a missing pad, and a missing pad is a number, so the number is decided here.
 */
class KeyboardFitTest {

    /** A gesture-navigation phone: a handle at the bottom, nothing down the sides. */
    @Test
    fun `the gesture handle is padded out from under the bottom row`() {
        val fit = KeyboardFit.padding(
            navigation = Edges(bottom = 48),
            cutout = Edges(),
            gestures = Edges(bottom = 48)
        )
        assertEquals(48, fit.bottom)
        assertEquals(0, fit.left)
        assertEquals(0, fit.right)
    }

    /**
     * The two answers disagree, so the larger wins. A phone that reports no navigation bar but does
     * reserve a gesture strip still gets the strip kept clear, and the other way round.
     */
    @Test
    fun `the bottom is whichever of the bar and the gesture strip asks for more`() {
        assertEquals(
            60,
            KeyboardFit.padding(Edges(bottom = 60), Edges(), Edges(bottom = 32)).bottom
        )
        assertEquals(
            60,
            KeyboardFit.padding(Edges(bottom = 0), Edges(), Edges(bottom = 60)).bottom
        )
    }

    /** Landscape with three-button navigation: the bar is down one edge, not along the bottom. */
    @Test
    fun `a navigation bar down one side is padded out from that side`() {
        val fit = KeyboardFit.padding(
            navigation = Edges(right = 44),
            cutout = Edges(),
            gestures = Edges()
        )
        assertEquals(44, fit.right)
        assertEquals(0, fit.left)
        assertEquals(0, fit.bottom)
    }

    /** A cap half under a camera hole is a cap nobody can read. */
    @Test
    fun `a display cutout wider than the bar is the one that decides the side`() {
        val fit = KeyboardFit.padding(
            navigation = Edges(left = 12, right = 44),
            cutout = Edges(left = 80, right = 0),
            gestures = Edges()
        )
        assertEquals(80, fit.left)
        assertEquals(44, fit.right)
    }

    /**
     * The top is never padded. There is no system furniture above a keyboard — the app being typed
     * into is there — and padding it would push the keys back down, which is the complaint.
     */
    @Test
    fun `the top is never padded, whatever the window reports`() {
        val fit = KeyboardFit.padding(
            navigation = Edges(top = 90, bottom = 48),
            cutout = Edges(top = 120),
            gestures = Edges(top = 90)
        )
        assertEquals(0, fit.top)
        assertEquals(48, fit.bottom)
    }

    /** Nothing to keep clear: the keyboard fills its window, as it did before there were insets. */
    @Test
    fun `a window with no furniture around it is not padded at all`() {
        assertEquals(Edges(), KeyboardFit.padding(Edges(), Edges(), Edges()))
    }

    /** A negative pad would pull the keyboard off the bottom of its own window. */
    @Test
    fun `a negative inset is floored rather than trusted`() {
        val fit = KeyboardFit.padding(
            navigation = Edges(left = -10, right = -10, bottom = -10),
            cutout = Edges(left = -20, right = -20),
            gestures = Edges(bottom = -20)
        )
        assertEquals(Edges(), fit)
    }
}
