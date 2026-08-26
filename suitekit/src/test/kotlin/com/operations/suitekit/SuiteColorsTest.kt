package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteColorsTest {

    @Test
    fun `parses every hex form the settings field accepts`() {
        assertEquals(0xFF4A5568L, SuiteColors.parseHex("#4A5568"))
        assertEquals(0xFF4A5568L, SuiteColors.parseHex("4a5568"))
        assertEquals(0xFF4A5568L, SuiteColors.parseHex("  #4A5568  "))
        assertEquals(0xFFAABBCCL, SuiteColors.parseHex("#ABC"))
        assertEquals(0x804A5568L, SuiteColors.parseHex("#804A5568"))
    }

    @Test
    fun `a half-typed or nonsense colour falls back instead of throwing`() {
        assertEquals(SuiteColors.FALLBACK, SuiteColors.parseHex("#3B1F5"))
        assertEquals(SuiteColors.FALLBACK, SuiteColors.parseHex("#zzzzzz"))
        assertEquals(SuiteColors.FALLBACK, SuiteColors.parseHex(""))
        assertEquals(SuiteColors.FALLBACK, SuiteColors.parseHex(null))
        assertEquals(0xFF2F855AL, SuiteColors.parseHex("nope", fallback = 0xFF2F855AL))
    }

    @Test
    fun `hex formatting round-trips, keeping alpha only when it matters`() {
        assertEquals("#4A5568", SuiteColors.toHex(0xFF4A5568L))
        assertEquals("#804A5568", SuiteColors.toHex(0x804A5568L))
        val original = "#2C7A7B"
        assertEquals(original, SuiteColors.toHex(SuiteColors.parseHex(original)))
    }

    @Test
    fun `lighten and darken land on the luminance they promise`() {
        val slate = 0xFF4A5568L
        val lum = SuiteColors.luminance(slate)
        assertEquals(lum + (1f - lum) * 0.5f, SuiteColors.luminance(SuiteColors.lighten(slate, 0.5f)), 0.01f)
        assertEquals(lum * 0.5f, SuiteColors.luminance(SuiteColors.darken(slate, 0.5f)), 0.01f)
        assertEquals(slate, SuiteColors.lighten(slate, 0f))
        assertEquals(0xFFFFFFFFL, SuiteColors.lighten(slate, 1f))
        assertEquals(0xFF000000L, SuiteColors.darken(slate, 1f))
    }

    @Test
    fun `blend moves between two colours and keeps the source alpha`() {
        val black = 0xFF000000L
        val white = 0xFFFFFFFFL
        assertEquals(black, SuiteColors.blend(black, white, 0f))
        assertEquals(white, SuiteColors.blend(black, white, 1f))
        val mid = SuiteColors.blend(black, white, 0.5f)
        assertEquals(0.5f, SuiteColors.luminance(mid), 0.01f)
        assertEquals(0x80, SuiteColors.alpha(SuiteColors.blend(0x80000000L, white, 0.5f)))
    }

    @Test
    fun `text colour flips at the brightness where it stops being readable`() {
        assertEquals(SuiteColors.SNOW, SuiteColors.contrastOn(0xFF111827L))
        assertEquals(SuiteColors.INK, SuiteColors.contrastOn(0xFFF9FAFBL))
        assertEquals(SuiteColors.INK, SuiteColors.contrastOn(0xFFFFCC02L))
    }

    @Test
    fun `an accent is lifted or dropped only as far as the mode needs`() {
        val darkGreen = 0xFF2F855AL
        val lifted = SuiteColors.fitForMode(darkGreen, dark = true)
        assertTrue(SuiteColors.luminance(lifted) >= SuiteColors.DARK_MODE_MIN_LUMINANCE - 0.01f)

        val paleTeal = 0xFF81E6D9L
        val dropped = SuiteColors.fitForMode(paleTeal, dark = false)
        assertTrue(SuiteColors.luminance(dropped) <= SuiteColors.LIGHT_MODE_MAX_LUMINANCE + 0.01f)

        // Already legible in that mode → returned untouched, so a chosen hue survives exactly.
        assertEquals(paleTeal, SuiteColors.fitForMode(paleTeal, dark = true))
        assertEquals(0xFF1B5E20L, SuiteColors.fitForMode(0xFF1B5E20L, dark = false))
    }
}
