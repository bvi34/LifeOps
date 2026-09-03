package com.lifeops.app.util

import com.operations.suitekit.SuiteHsl
import kotlin.math.min

/**
 * The Growth Record's "colour by hours" intensity mapping.
 *
 * The hex <-> HSL arithmetic underneath is the suite's ([SuiteHsl]) — one conversion, shared with
 * the colour picker that produced these hexes in the first place. What stays here is the part that
 * is only about growth rings: how much an aspect's colour brightens with the hours put into it.
 */
object GrowthColor {

    fun hexToRgb(hex: String): Triple<Int, Int, Int> = SuiteHsl.hexToRgb(hex)

    fun rgbToHex(r: Int, g: Int, b: Int): String = SuiteHsl.rgbToHex(r, g, b)

    fun hexToHsl(hex: String): SuiteHsl.Hsl = SuiteHsl.hexToHsl(hex)

    fun hslToHex(h: Double, s: Double, l: Double): String = SuiteHsl.hslToHex(h, s, l)

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
    fun lighten(hex: String, by: Double = 0.06): String = SuiteHsl.lighten(hex, by)
}
