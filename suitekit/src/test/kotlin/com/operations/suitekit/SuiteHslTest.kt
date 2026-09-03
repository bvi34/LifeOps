package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour picker's arithmetic. What matters here is that a colour survives the trip: somebody
 * taps a swatch, the sliders read it back as hue/saturation/lightness, and what gets stored is the
 * same string they tapped — not a shade that drifts a little further every time the dialog is opened.
 */
class SuiteHslTest {

    @Test
    fun `every palette swatch round-trips through HSL exactly`() {
        SuiteSwatches.PALETTE.forEach { swatch ->
            val hsl = SuiteHsl.hexToHsl(swatch)
            assertEquals(swatch, SuiteHsl.hslToHex(hsl.h, hsl.s, hsl.l))
        }
    }

    @Test
    fun `normalising cleans up whatever the field was holding`() {
        assertEquals("#6200EE", SuiteHsl.normalizeHex("#6200ee"))
        assertEquals("#6200EE", SuiteHsl.normalizeHex("6200EE"))
        assertEquals("#AABBCC", SuiteHsl.normalizeHex("#abc"))
        // Unreadable input resolves to the suite's fallback rather than taking a screen down.
        assertEquals("#6200EE", SuiteHsl.normalizeHex(""))
        assertEquals("#6200EE", SuiteHsl.normalizeHex("nonsense"))
    }

    @Test
    fun `black and white are achromatic, not a hue that happens to be invisible`() {
        assertEquals(0.0, SuiteHsl.hexToHsl("#FFFFFF").s, 1e-9)
        assertEquals(1.0, SuiteHsl.hexToHsl("#FFFFFF").l, 1e-9)
        assertEquals(0.0, SuiteHsl.hexToHsl("#000000").s, 1e-9)
        assertEquals("#FFFFFF", SuiteHsl.hslToHex(0.0, 0.0, 1.0))
        assertEquals("#000000", SuiteHsl.hslToHex(0.0, 0.0, 0.0))
    }

    @Test
    fun `hue wraps rather than clipping, so a slider dragged past 360 keeps going`() {
        assertEquals(SuiteHsl.hslToHex(20.0, 0.5, 0.5), SuiteHsl.hslToHex(380.0, 0.5, 0.5))
        assertEquals(SuiteHsl.hslToHex(340.0, 0.5, 0.5), SuiteHsl.hslToHex(-20.0, 0.5, 0.5))
    }

    @Test
    fun `lighten raises lightness without moving the hue`() {
        val base = "#43A047"
        val lighter = SuiteHsl.lighten(base)
        assertNotEquals(base, lighter)
        assertEquals(SuiteHsl.hexToHsl(base).h, SuiteHsl.hexToHsl(lighter).h, 0.5)
        assertTrue(SuiteHsl.hexToHsl(lighter).l > SuiteHsl.hexToHsl(base).l)
        // Never all the way to white: a "lighter" swatch you cannot see is not a swatch.
        assertTrue(SuiteHsl.hexToHsl(SuiteHsl.lighten("#FFFFFF", 0.9)).l <= 0.95)
    }

    @Test
    fun `the next swatch skips what is taken and cycles once they all are`() {
        assertEquals(SuiteSwatches.PALETTE[0], SuiteSwatches.next(emptyList()))
        // Case is not identity: a colour stored lowercase is still that colour.
        assertEquals(SuiteSwatches.PALETTE[2], SuiteSwatches.next(listOf("#6200ee", "#00bfa5")))
        assertEquals(SuiteSwatches.PALETTE[0], SuiteSwatches.next(SuiteSwatches.PALETTE))
    }

    @Test
    fun `the palette is distinct, or two aspects would be the same colour`() {
        val normalised = SuiteSwatches.PALETTE.map { SuiteHsl.normalizeHex(it) }
        assertEquals(SuiteSwatches.PALETTE.size, normalised.toSet().size)
    }
}
