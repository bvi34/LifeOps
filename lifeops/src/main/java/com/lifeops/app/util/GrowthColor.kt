package com.lifeops.app.util

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure hex <-> HSL helpers and the "colour by hours" intensity mapping for the Growth
 * Record rings. Deliberately free of Android dependencies so the maths is unit-testable
 * on the JVM (and reusable by the SVG export path).
 */
object GrowthColor {

    data class Hsl(val h: Double, val s: Double, val l: Double)

    /** #6200EE — same fallback the rest of the app uses when a colour can't be parsed. */
    private val FALLBACK_RGB = Triple(98, 0, 238)

    fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val clean = hex.trim().removePrefix("#")
        return try {
            when (clean.length) {
                6 -> {
                    val v = clean.toLong(16)
                    Triple(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
                }
                3 -> Triple(
                    clean.substring(0, 1).repeat(2).toInt(16),
                    clean.substring(1, 2).repeat(2).toInt(16),
                    clean.substring(2, 3).repeat(2).toInt(16)
                )
                else -> FALLBACK_RGB
            }
        } catch (_: NumberFormatException) {
            FALLBACK_RGB
        }
    }

    fun rgbToHex(r: Int, g: Int, b: Int): String =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

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

    /**
     * Colour-by-hours intensity, ported verbatim from the prototype:
     *
     *   f  = 0.5 + 0.01 * hours          (0 h -> 0.5, 50 h -> 1.0, uncapped above)
     *   S' = clamp(baseS * f, 0, 1)
     *   if f > 1: L' = clamp(baseL + min(0.16, (f-1)*0.08), 0, 0.92)   (glow spillover)
     *
     * The 0.5 intercept is the anti-punishment floor: an aspect you barely touched is
     * muted, not black. Hue is never altered, so each aspect stays recognisable.
     */
    fun intensify(baseHex: String, hours: Double): String {
        val base = hexToHsl(baseHex)
        val f = 0.5 + 0.01 * hours
        val s = (base.s * f).coerceIn(0.0, 1.0)
        val l = if (f > 1.0) (base.l + min(0.16, (f - 1.0) * 0.08)).coerceIn(0.0, 0.92) else base.l
        return hslToHex(base.h, s, l)
    }

    /** A slightly lightened variant of [hex], used for the radial-gradient background centre. */
    fun lighten(hex: String, by: Double = 0.06): String {
        val c = hexToHsl(hex)
        return hslToHex(c.h, c.s, (c.l + by).coerceIn(0.0, 0.95))
    }
}
