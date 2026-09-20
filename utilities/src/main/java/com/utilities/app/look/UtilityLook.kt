package com.utilities.app.look

/**
 * How a screen Utilities has taken over is *set*: its colours, its type, its shapes.
 *
 * This is Citation's [com.citation.core.reader.ReaderSettings] idea, moved to a different room and
 * kept deliberately separate from it. Citation's reader is the one place in the suite where somebody
 * sat down and asked what a person actually needs to change about a surface they stare at for an
 * hour — not a dark-mode switch and a font size, but a page colour they chose themselves, a warmth
 * curve that cuts blue rather than dimming the panel, a face loaded from a file because the one they
 * need (OpenDyslexic, most often) is not one the suite may ship. Everything a keyboard or a message
 * thread needs is the same list.
 *
 * It is a copy rather than a dependency, and that is the considered choice: `:core` is *Citation's*
 * spine — the sync contract, the paginator, the note model — and a keyboard reaching into it for a
 * colour would be an arrow nobody could defend on the day Citation's reader settings grow a field
 * about chapters. What is borrowed is the design; the thirty lines of colour maths in
 * [UtilityPalettes] are worth owning outright. If a third app ever wants them, the place they go is
 * `:suitekit`, which is already the suite's pure-JVM appearance contract.
 *
 * Pure JVM, like everything in this package: what warmth does to a colour and what happens when
 * somebody picks an unreadable one are decided here, and tested without a device.
 */
data class UtilityLook(
    // --- Colour ------------------------------------------------------------------------------
    val theme: UtilityTheme = UtilityTheme.SYSTEM,
    /** The surface colour, ARGB, under [UtilityTheme.CUSTOM]. Held while another theme is chosen. */
    val surfaceColor: Int? = null,
    /** The text colour, ARGB, under [UtilityTheme.CUSTOM]. See [surfaceColor]. */
    val textColor: Int? = null,
    /**
     * The one colour that is *not* the page: a sent bubble, a pressed key, the send button.
     *
     * Its own field because it is the colour people mean when they say they want an app to look
     * like theirs, and because deriving it from the surface — which is what a theme without one has
     * to do — reliably produces something nobody would have picked.
     */
    val accentColor: Int? = null,
    /** Pure black rather than near-black, so an OLED panel actually switches those pixels off. */
    val trueBlack: Boolean = false,
    /** 0 = untouched, 1 = strongly amber. Cuts blue light without dimming the panel. */
    val warmth: Float = 0f,

    // --- Type --------------------------------------------------------------------------------
    val typeface: UtilityTypeface = UtilityTypeface.SANS,
    /** A font file the household supplied. See [UtilityTypeface.CUSTOM]. */
    val fontPath: String? = null,
    /** Multiplies the size of every piece of text on the surface. */
    val textScale: Float = 1f,

    // --- Shape -------------------------------------------------------------------------------
    /** How round the things on this surface are: a bubble, a key, a card. */
    val cornerDp: Float = 18f,
    /** How much air is around them. Negative is tighter than the default, positive roomier. */
    val paddingDp: Float = 0f
) {

    /** Clamp every value into a range that can actually be rendered. */
    fun sanitized(): UtilityLook = copy(
        warmth = warmth.coerceIn(0f, 1f),
        textScale = textScale.coerceIn(0.7f, 2.0f),
        cornerDp = cornerDp.coerceIn(0f, 32f),
        paddingDp = paddingDp.coerceIn(-4f, 12f),
        // A part-transparent surface lets whatever is behind it show through, which is never what
        // somebody picking a colour meant. Keep the hue, drop the transparency.
        surfaceColor = surfaceColor?.let { UtilityPalettes.opaque(it) },
        textColor = textColor?.let { UtilityPalettes.opaque(it) },
        accentColor = accentColor?.let { UtilityPalettes.opaque(it) }
    )
}

/**
 * The presets, and the escape hatch.
 *
 * [SYSTEM] is the suite's own colours — whatever the sandbox's appearance settings resolved to —
 * and is the default, because a utility that looked nothing like the eleven apps around it would be
 * a utility that looked broken. The other three are the ones people actually ask for by name.
 * [CUSTOM] exists for the same reason Citation's does: a reader with Irlen syndrome or a light
 * sensitivity is told a specific tint helps, and no preset anybody ships is going to be it.
 */
enum class UtilityTheme(val label: String) {
    SYSTEM("System"),
    PAPER("Paper"),
    SEPIA("Sepia"),
    NIGHT("Night"),
    CUSTOM("Custom")
}

/** The faces a taken-over surface can be set in. */
enum class UtilityTypeface(val label: String) {
    SANS("Sans"),
    SERIF("Serif"),
    MONO("Mono"),

    /**
     * A face the household supplied, named by [UtilityLook.fontPath]. Falls back to sans when the
     * file has gone — a missing font must never make a keyboard undrawable.
     */
    CUSTOM("Your font")
}
