package com.utilities.app.keyboard.logic

import kotlin.math.max

/**
 * How much room the system's own furniture needs around the keys.
 *
 * An input method's window is put up edge to edge: it reaches the bottom of the screen, under the
 * navigation bar, and nothing pads it on the keyboard's behalf. A keyboard that ignores that draws
 * its last row — the layer toggle, the space bar, the enter key, the three most-pressed keys on the
 * board — in the strip the phone reserves for the gesture handle and the keyboard-switch button, so
 * the system's own glyphs land on top of the caps and a press near the bottom of the space bar goes
 * to the gesture rather than to the keyboard. That is what "the keyboard is too low" is.
 *
 * The rule is the obvious one and it is here, rather than inline in the service, because it is
 * arithmetic on four rectangles and arithmetic is worth a test:
 *
 *  - **the bottom** is the navigation bar, or the mandatory gesture strip, whichever asks for more.
 *    The two agree on most phones; on a gesture-navigation phone in an orientation where the bar
 *    reports nothing, the gesture inset is the one that is right;
 *  - **the sides** are the navigation bar again — it is down one edge in landscape with three-button
 *    navigation — or the display cutout, whichever is wider. A cap half under a camera hole is a cap
 *    nobody can read;
 *  - **the top is never padded.** The keyboard's top edge is where the app being typed into ends;
 *    there is no system furniture there, and padding it would just push the keys down again.
 */
object KeyboardFit {

    /** One rectangle of insets, in pixels. Deliberately not `android.graphics.Insets` — see above. */
    data class Edges(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    /**
     * The padding to put around the keys, given what the window reports.
     *
     * Negative insets are not a thing the platform sends, but a keyboard that padded by a negative
     * number would pull itself off the bottom of its own window, so they are floored at zero here
     * rather than trusted.
     */
    fun padding(navigation: Edges, cutout: Edges, gestures: Edges): Edges = Edges(
        left = atLeastZero(max(navigation.left, cutout.left)),
        top = 0,
        right = atLeastZero(max(navigation.right, cutout.right)),
        bottom = atLeastZero(max(navigation.bottom, gestures.bottom))
    )

    private fun atLeastZero(value: Int): Int = max(value, 0)
}
