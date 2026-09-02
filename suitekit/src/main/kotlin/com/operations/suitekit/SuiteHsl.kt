package com.operations.suitekit

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hex <-> HSL, on plain strings, with no Compose and no `android.graphics`.
 *
 * The suite's colour picker is a hue/saturation/lightness picker, and every app that stores a colour
 * stores it as `#RRGGBB` text. This is the conversion between those two facts, kept on the JVM side
 * so the maths is unit-testable and so a non-UI caller (an export path, a palette suggestion) can
 * reach it without a Compose dependency.
 *
 * Parsing is total, never throwing: these strings arrive from stored documents and half-typed text
 * fields, so anything unreadable resolves to [SuiteColors.FALLBACK] rather than taking a screen down.
 */
object SuiteHsl {

    data class Hsl(val h: Double, val s: Double, val l: Double)

    fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val color = SuiteColors.parseHex(hex)
        return Triple(SuiteColors.red(color), SuiteColors.green(color), SuiteColors.blue(color))
    }

    fun rgbToHex(r: Int, g: Int, b: Int): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Any input — `#abc`, `abcdef`, junk — as one clean, opaque `#RRGGBB`. */
    fun normalizeHex(hex: String): String {
        val (r, g, b) = hexToRgb(hex)
        return rgbToHex(r, g, b)
    }

    fun hexToHsl(hex: String): Hsl {
        val (r, g, b) = hexToRgb(hex)
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2.0
        if (max == min) return Hsl(0.0, 0.0, l) // achromatic
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            rf -> (gf - bf) / d + (if (gf < bf) 6.0 else 0.0)
            gf -> (bf - rf) / d + 2.0
            else -> (rf - gf) / d + 4.0
        } * 60.0
        return Hsl(h, s, l)
    }

    fun hslToHex(h: Double, s: Double, l: Double): String {
        val hue = ((h % 360.0) + 360.0) % 360.0
        if (s <= 0.0) {
            val v = (l * 255.0).roundToInt()
            return rgbToHex(v, v, v)
        }
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - abs((hue / 60.0) % 2.0 - 1.0))
        val m = l - c / 2.0
        val (r1, g1, b1) = when {
            hue < 60 -> Triple(c, x, 0.0)
            hue < 120 -> Triple(x, c, 0.0)
            hue < 180 -> Triple(0.0, c, x)
            hue < 240 -> Triple(0.0, x, c)
            hue < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return rgbToHex(
            ((r1 + m) * 255.0).roundToInt(),
            ((g1 + m) * 255.0).roundToInt(),
            ((b1 + m) * 255.0).roundToInt()
        )
    }

    /** A slightly lightened variant of [hex] — the one HSL nudge several apps make by hand. */
    fun lighten(hex: String, by: Double = 0.06): String {
        val c = hexToHsl(hex)
        return hslToHex(c.h, c.s, (c.l + by).coerceIn(0.0, 0.95))
    }
}

/**
 * The swatches the suite's colour picker offers, and the rule for handing out the next unused one.
 *
 * One list, because a colour chosen in LifeOps and a colour chosen in Health end up next to each
 * other on the sandbox home screen; twelve hues that are distinct from each other *and* legible in
 * both light and dark are a decision worth making once.
 */
object SuiteSwatches {

    val PALETTE: List<String> = listOf(
        "#6200EE", // Purple
        "#00BFA5", // Teal
        "#FF6D00", // Orange
        "#D81B60", // Pink
        "#43A047", // Green
        "#1E88E5", // Blue
        "#F4511E", // Deep Orange
        "#8E24AA", // Violet
        "#00ACC1", // Cyan
        "#FDD835", // Yellow
        "#6D4C41", // Brown
        "#5C6BC0"  // Indigo
    )

    /**
     * The first palette colour [used] does not already contain, cycling once they are all taken.
     *
     * Cycling rather than repeating the first: a household that has run past twelve aspects is
     * better served by a colour it has seen once than by twelve identical purple rings.
     */
    fun next(used: List<String>): String {
        val taken = used.map { it.uppercase() }.toSet()
        return PALETTE.firstOrNull { it.uppercase() !in taken }
            ?: PALETTE[used.size % PALETTE.size]
    }
}
