package com.utilities.app.keyboard.logic

import com.utilities.app.look.UtilityLook

/**
 * How the keyboard is drawn and how it behaves, on top of the suite-wide [UtilityLook].
 *
 * Same split as the message thread's: the colours, warmth, face and corner radius are the shared
 * value, so setting the keyboard to match the threads is one gesture. What is here is what only a
 * keyboard has — how tall it is, whether the caps have edges, how long a long press is.
 *
 * The three at the bottom are the privacy switches, and they are settings rather than constants for
 * one reason: somebody who wants a keyboard that remembers *nothing at all* should be able to have
 * it, and should not have to trust a paragraph about how the remembering is safe. Turning [learn]
 * off empties the list as well as stopping it growing (see the settings screen) — a switch that
 * only stopped the future would leave the past sitting there.
 */
data class KeyboardLook(
    val look: UtilityLook = UtilityLook(),

    /** Multiplies the keyboard's height. Some people want thumbs-reach; some want the screen. */
    val heightScale: Float = 1f,

    /** A row of digits across the top. Off by default — see [KeyLayouts]. */
    val numberRow: Boolean = false,

    /**
     * Draw each cap as a raised key rather than a letter floating on the surface.
     *
     * On by default. Edgeless keyboards look better in a screenshot and are measurably worse to aim
     * at, and the households most likely to be typing on a phone in poor light are the ones the
     * edges help most.
     */
    val keyEdges: Boolean = true,

    /** The row of completions above the keys. */
    val suggestions: Boolean = true,

    /** Remember the words typed here, to suggest them later. See [Lexicon]. */
    val learn: Boolean = true,

    /** Capitalise the first letter of a sentence. */
    val autoCapitalize: Boolean = true,

    val haptics: Boolean = true,

    /** How long a press has to be held to type the alternate printed on the cap. */
    val longPressMs: Int = 300
) {
    fun sanitized(): KeyboardLook = copy(
        look = look.sanitized(),
        heightScale = heightScale.coerceIn(0.7f, 1.6f),
        longPressMs = longPressMs.coerceIn(150, 800)
    )

    /** Suggestions with nothing to suggest from are a strip of blank space. */
    val showsSuggestions: Boolean get() = suggestions
}
