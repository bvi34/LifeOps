package com.operations.suitekit

/**
 * The suite's colour arithmetic, on plain `0xAARRGGBB` longs so it can be reasoned about (and
 * tested) without Compose or `android.graphics`.
 *
 * Two properties the rest of the appearance layer leans on:
 *  - [lighten] and [darken] are *affine per channel*, and [luminance] is a linear combination of the
 *    channels, so `lighten(c, f)` lands on exactly `lum + (1 - lum) * f` and `darken(c, f)` on
 *    exactly `lum * (1 - f)`. [fitForMode] inverts those to hit a target brightness in one step.
 *  - every colour handed to the UI is opaque; alpha is preserved by the maths but the palette
 *    parser defaults to `0xFF` so a user typing `#4A5568` can never produce an invisible surface.
 */
object SuiteColors {

    /** The suite's two text anchors — near-black and near-white, shared by every preset. */
    const val INK = 0xFF111827L
    const val SNOW = 0xFFF9FAFBL

    /** Fallback for an unparseable hex string: LifeOps' long-standing purple. */
    const val FALLBACK = 0xFF6200EEL

    fun alpha(color: Long): Int = ((color shr 24) and 0xFF).toInt()
    fun red(color: Long): Int = ((color shr 16) and 0xFF).toInt()
    fun green(color: Long): Int = ((color shr 8) and 0xFF).toInt()
    fun blue(color: Long): Int = (color and 0xFF).toInt()

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Long =
        ((alpha.coerceIn(0, 255).toLong() shl 24) or
            (red.coerceIn(0, 255).toLong() shl 16) or
            (green.coerceIn(0, 255).toLong() shl 8) or
            blue.coerceIn(0, 255).toLong())

    /**
     * Parse `#RGB`, `#RRGGBB` or `#AARRGGBB` (the leading `#` optional, case-insensitive). Anything
     * else — empty, too short, non-hex — yields [fallback] rather than throwing, because these
     * strings come from a text field the user is still typing into.
     */
    fun parseHex(hex: String?, fallback: Long = FALLBACK): Long {
        val raw = hex?.trim()?.removePrefix("#") ?: return fallback
        if (raw.any { it.digitToIntOrNull(16) == null }) return fallback
        return when (raw.length) {
            3 -> {
                val r = raw[0].digitToInt(16); val g = raw[1].digitToInt(16); val b = raw[2].digitToInt(16)
                argb(0xFF, r * 17, g * 17, b * 17)
            }
            6 -> 0xFF000000L or raw.toLong(16)
            8 -> raw.toLong(16)
            else -> fallback
        }
    }

    /** Format as `#RRGGBB`, or `#AARRGGBB` when the colour is not fully opaque. */
    fun toHex(color: Long): String {
        val body = "%02X%02X%02X".format(red(color), green(color), blue(color))
        return if (alpha(color) == 0xFF) "#$body" else "#%02X%s".format(alpha(color), body)
    }

    /** Relative brightness in `0f..1f` (Rec. 709 weights, no gamma — good enough to pick text). */
    fun luminance(color: Long): Float =
        (0.2126f * red(color) + 0.7152f * green(color) + 0.0722f * blue(color)) / 255f

    /** [INK] or [SNOW], whichever stays readable on [color]. */
    fun contrastOn(color: Long): Long = if (luminance(color) > 0.45f) INK else SNOW

    /** Blend towards white by [fraction] (0 = unchanged, 1 = white). Alpha is preserved. */
    fun lighten(color: Long, fraction: Float): Long {
        val f = fraction.coerceIn(0f, 1f)
        fun ch(v: Int) = (v + (255 - v) * f).toInt()
        return argb(alpha(color), ch(red(color)), ch(green(color)), ch(blue(color)))
    }

    /** Blend towards black by [fraction] (0 = unchanged, 1 = black). Alpha is preserved. */
    fun darken(color: Long, fraction: Float): Long {
        val f = fraction.coerceIn(0f, 1f)
        fun ch(v: Int) = (v * (1f - f)).toInt()
        return argb(alpha(color), ch(red(color)), ch(green(color)), ch(blue(color)))
    }

    /** Mix [color] towards [toward] by [fraction]; keeps [color]'s alpha. */
    fun blend(color: Long, toward: Long, fraction: Float): Long {
        val f = fraction.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return argb(
            alpha(color),
            ch(red(color), red(toward)),
            ch(green(color), green(toward)),
            ch(blue(color), blue(toward))
        )
    }

    /**
     * Nudge an app's identity colour until it is legible as an accent in the current mode: light
     * enough to read on a dark background, dark enough to read on a light one. A colour already in
     * range is returned untouched, so a well-chosen accent keeps exactly the hue the user picked.
     */
    fun fitForMode(color: Long, dark: Boolean): Long {
        val lum = luminance(color)
        return if (dark) {
            if (lum >= DARK_MODE_MIN_LUMINANCE) color
            else lighten(color, (DARK_MODE_MIN_LUMINANCE - lum) / (1f - lum))
        } else {
            if (lum <= LIGHT_MODE_MAX_LUMINANCE) color
            else darken(color, (lum - LIGHT_MODE_MAX_LUMINANCE) / lum)
        }
    }

    const val DARK_MODE_MIN_LUMINANCE = 0.45f
    const val LIGHT_MODE_MAX_LUMINANCE = 0.42f
}
