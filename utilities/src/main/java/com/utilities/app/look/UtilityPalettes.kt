package com.utilities.app.look

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every colour a taken-over surface is drawn in, for one [UtilityLook].
 *
 * [surface] is the backdrop, [text] what is written on it, [accent] the one colour that is not
 * either — a sent bubble, a pressed key. [muted] is the half-there text: a timestamp, a hint on a
 * key. [line] is a divider or a key's edge, and is derived rather than chosen because nobody has
 * ever wanted to pick one.
 */
data class UtilityPalette(
    val surface: Int,
    val text: Int,
    val accent: Int,
    val onAccent: Int,
    val muted: Int,
    val line: Int
) {
    /** Whether the surface is dark, which is what everything drawn *on top* has to know. */
    val dark: Boolean get() = UtilityPalettes.luminance(surface) < 0.5f
}

/**
 * The colour maths, as plain ARGB and with no framework anywhere near it.
 *
 * Borrowed in design from Citation's `ReaderPalette` — see [UtilityLook] for why it is a copy — and
 * the interesting parts are the same two:
 *
 *  - **warming is done to the colours, not with an overlay.** A translucent orange sheet dims
 *    everything under it, which flattens contrast at exactly the hour somebody turned the warmth up
 *    because their eyes were tired. Cutting blue out of each colour warms the surface while leaving
 *    it as legible as it was.
 *  - **the accent is checked against the surface it lands on.** Somebody who tints a keyboard deep
 *    green and leaves the accent alone gets an accent that is moved until it can be read, rather
 *    than a send button that has disappeared.
 */
object UtilityPalettes {

    // The four preset surfaces and what is written on them.
    const val PAPER_SURFACE = 0xFFF7F3EA.toInt()
    const val PAPER_TEXT = 0xFF23201B.toInt()
    const val SEPIA_SURFACE = 0xFFF4E6CE.toInt()
    const val SEPIA_TEXT = 0xFF4A3A28.toInt()
    const val NIGHT_SURFACE = 0xFF16181C.toInt()
    const val NIGHT_TEXT = 0xFFDCDFE4.toInt()
    const val BLACK = 0xFF000000.toInt()
    /** On a true-black panel, not quite white: pure white on pure black is what causes halation. */
    const val TRUE_BLACK_TEXT = 0xFFCFD3D8.toInt()

    /** Where a custom theme starts before anybody has moved a slider. */
    const val CUSTOM_SURFACE = 0xFFFFFDF7.toInt()
    const val CUSTOM_TEXT = 0xFF1A1A1A.toInt()

    /** The accent a theme falls back to when the household has not picked one. */
    const val DEFAULT_ACCENT = 0xFF1D4ED8.toInt()

    /** WCAG AA for large text, which is what a bubble, a key cap and a button all are. */
    const val MIN_CONTRAST = 3.0f

    private const val BLUE_CUT = 0.42f
    private const val GREEN_CUT = 0.14f
    private const val MUTED_FADE = 0.42f
    private const val LINE_FADE = 0.80f
    private val RESCUE_BLENDS = listOf(0.25f, 0.5f, 0.75f, 1f)

    /** The surface for a theme, or null under [UtilityTheme.SYSTEM] — "whatever the app is using". */
    fun surface(theme: UtilityTheme, trueBlack: Boolean, custom: Int? = null): Int? = when {
        theme == UtilityTheme.NIGHT && trueBlack -> BLACK
        theme == UtilityTheme.NIGHT -> NIGHT_SURFACE
        theme == UtilityTheme.PAPER -> PAPER_SURFACE
        theme == UtilityTheme.SEPIA -> SEPIA_SURFACE
        theme == UtilityTheme.CUSTOM -> opaque(custom ?: CUSTOM_SURFACE)
        else -> null
    }

    /** The text colour for a theme, or null under [UtilityTheme.SYSTEM]. See [surface]. */
    fun text(theme: UtilityTheme, trueBlack: Boolean, custom: Int? = null): Int? = when {
        theme == UtilityTheme.NIGHT && trueBlack -> TRUE_BLACK_TEXT
        theme == UtilityTheme.NIGHT -> NIGHT_TEXT
        theme == UtilityTheme.PAPER -> PAPER_TEXT
        theme == UtilityTheme.SEPIA -> SEPIA_TEXT
        theme == UtilityTheme.CUSTOM -> opaque(custom ?: CUSTOM_TEXT)
        else -> null
    }

    /**
     * The whole palette for [look], falling back to the colours the app itself is using wherever
     * the theme has nothing to say.
     *
     * The fallbacks are what makes [UtilityTheme.SYSTEM] mean "the suite's own colours" without the
     * rest of the palette being pulled out of a Material scheme: the muted text and the divider are
     * still derived from the surface that will actually be on screen, and the accent is still
     * checked against it.
     */
    fun resolve(
        look: UtilityLook,
        fallbackSurface: Int,
        fallbackText: Int,
        fallbackAccent: Int = DEFAULT_ACCENT
    ): UtilityPalette {
        val clean = look.sanitized()
        val base = surface(clean.theme, clean.trueBlack, clean.surfaceColor) ?: fallbackSurface
        val ink = text(clean.theme, clean.trueBlack, clean.textColor) ?: fallbackText
        val warmSurface = warm(base, clean.warmth)
        val warmText = warm(ink, clean.warmth)
        val wanted = warm(clean.accentColor ?: fallbackAccent, clean.warmth)
        val accent = readable(wanted, warmSurface, warmText)
        return UtilityPalette(
            surface = warmSurface,
            text = warmText,
            accent = accent,
            onAccent = contrastOn(accent),
            muted = mix(warmSurface, warmText, MUTED_FADE),
            line = mix(warmSurface, warmText, 1f - LINE_FADE)
        )
    }

    /**
     * Warm a colour by cutting its blue: blue drops most, green a little, red not at all.
     *
     * This is what a night-shift filter does, done to the colour rather than over it. See the
     * object note for why that distinction is the whole feature.
     */
    fun warm(argb: Int, warmth: Float): Int {
        val w = warmth.coerceIn(0f, 1f)
        if (w <= 0f) return argb
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val wg = (g * (1f - GREEN_CUT * w)).toInt().coerceIn(0, 255)
        val wb = (b * (1f - BLUE_CUT * w)).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (wg shl 8) or wb
    }

    /**
     * [tint] if it can be read on [on], otherwise the nearest version of it that can — pulled toward
     * [fallback], the colour the surface's own text is set in, until there is enough contrast.
     *
     * A tint that cannot be rescued gives way to the text colour entirely: an accent nobody can read
     * is worse than one nobody can pick out.
     */
    fun readable(tint: Int, on: Int, fallback: Int): Int {
        if (contrast(tint, on) >= MIN_CONTRAST) return opaque(tint)
        RESCUE_BLENDS.forEach { amount ->
            val shade = mix(fallback, tint, 1f - amount)
            if (contrast(shade, on) >= MIN_CONTRAST) return shade
        }
        return opaque(fallback)
    }

    /**
     * Near-black or near-white, whichever can actually be read on [argb].
     *
     * Chosen by measuring both rather than by a luminance threshold, which is the same decision for
     * almost every colour and a materially better one at the crossover: a threshold at 0.45 hands a
     * surface of luminance 0.44 the light ink, at a contrast of about 2 : 1, and that is exactly the
     * mid-grey bubble whose text nobody can read. Picking the better of the two puts the worst case
     * at the point where they are equal, which is above 4 : 1 — so there is no surface anywhere in
     * this app that gets unreadable text on it.
     */
    fun contrastOn(argb: Int): Int {
        val ink = INK
        val snow = SNOW
        return if (contrast(ink, argb) >= contrast(snow, argb)) ink else snow
    }

    /** The two ends of the suite's own ink, matching `SuiteColors`. */
    const val INK = 0xFF111827.toInt()
    const val SNOW = 0xFFF9FAFB.toInt()

    /** [a] blended [amount] of the way toward [b]. Alpha comes from [a]; both ends are opaque. */
    fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val from = (a ushr shift) and 0xFF
            val to = (b ushr shift) and 0xFF
            return (from + (to - from) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The same colour, fully opaque. */
    fun opaque(argb: Int): Int = (argb or (0xFF shl 24))

    /** Relative luminance, 0..1, by the sRGB formula the contrast ratio is defined against. */
    fun luminance(argb: Int): Float {
        fun channel(v: Int): Float {
            val c = v / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        val r = channel((argb ushr 16) and 0xFF)
        val g = channel((argb ushr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** The WCAG contrast ratio between two colours, 1..21. */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** Whether two colours are close enough that using both would look like a mistake. */
    fun indistinguishable(a: Int, b: Int): Boolean = abs(luminance(a) - luminance(b)) < 0.02f
}
