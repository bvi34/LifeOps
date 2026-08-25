package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of "display settings" that are actually decisions rather than plumbing: what warmth
 * does to a colour, what true black is for, and which volume key goes forward.
 */
class ReaderSettingsTest {

    private fun rgb(argb: Int) = Triple((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `defaults are what a book should open as`() {
        val s = ReaderSettings()
        assertEquals(ReaderTheme.SYSTEM, s.theme)
        assertEquals(ReaderTypeface.SERIF, s.typeface)
        assertEquals(ParagraphSpacing.INDENT, s.paragraphs)
        assertTrue("paged is the reading mode, scroll is the alternative", s.paged)
        assertTrue("a reader should not dim mid-paragraph", s.keepAwake)
        assertTrue(s.followsSystemBrightness)
        assertFalse("justification without care opens rivers on a phone column", s.justify)
        assertTrue(s.hyphenate)
    }

    @Test
    fun `values are clamped to what can actually be rendered`() {
        val wild = ReaderSettings(
            fontSize = 900f, lineSpacing = 0.1f, marginDp = -40f,
            letterSpacing = 12f, warmth = 5f, brightness = 4f
        ).sanitized()
        assertEquals(36f, wild.fontSize)
        assertEquals(1.0f, wild.lineSpacing)
        assertEquals(0f, wild.marginDp)
        assertEquals(0.4f, wild.letterSpacing)
        assertEquals(1f, wild.warmth)
        assertEquals(1f, wild.brightness)
    }

    @Test
    fun `a negative brightness stays the follow-the-system sentinel rather than being clamped to a value`() {
        val s = ReaderSettings(brightness = -0.5f).sanitized()
        assertTrue(s.followsSystemBrightness)
        assertEquals(ReaderSettings.SYSTEM_BRIGHTNESS, s.brightness)
    }

    // --- Warmth ------------------------------------------------------------------------------

    @Test
    fun `no warmth leaves a colour exactly alone`() {
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.warm(ReaderPalette.PAPER_BG, 0f))
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.warm(ReaderPalette.PAPER_BG, -1f))
    }

    @Test
    fun `warmth cuts blue hardest, green a little, red not at all`() {
        val warmed = ReaderPalette.warm(0xFF808080.toInt(), 1f)
        val (r, g, b) = rgb(warmed)
        assertEquals("red is untouched", 0x80, r)
        assertTrue("green drops a little", g in 0x72..0x74)
        assertTrue("blue drops most", b in 0x46..0x48)
        assertTrue("blue must fall further than green", b < g)
    }

    @Test
    fun `warmth preserves the alpha channel`() {
        assertEquals(0xFF, (ReaderPalette.warm(0xFF3366CC.toInt(), 0.6f) ushr 24) and 0xFF)
        assertEquals(0x80, (ReaderPalette.warm(0x803366CC.toInt(), 0.6f) ushr 24) and 0xFF)
    }

    @Test
    fun `warming is monotonic in the amount asked for`() {
        val base = 0xFFFFFFFF.toInt()
        val blues = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { ReaderPalette.warm(base, it) and 0xFF }
        assertEquals(blues.sortedDescending(), blues)
    }

    @Test
    fun `both of a theme's colours warm together`() {
        val (bg, fg) = ReaderPalette.of(ReaderSettings(theme = ReaderTheme.PAPER, warmth = 0.8f))!!
        assertNotEquals(ReaderPalette.PAPER_BG, bg)
        assertNotEquals(ReaderPalette.PAPER_FG, fg)
        // Warming must not invert the page: paper stays lighter than its ink.
        assertTrue((bg and 0xFF) > (fg and 0xFF))
    }

    // --- Themes ------------------------------------------------------------------------------

    @Test
    fun `the system theme defers rather than inventing colours`() {
        assertNull(ReaderPalette.background(ReaderTheme.SYSTEM, trueBlack = false))
        assertNull(ReaderPalette.foreground(ReaderTheme.SYSTEM, trueBlack = false))
        assertNull(ReaderPalette.of(ReaderSettings(theme = ReaderTheme.SYSTEM)))
    }

    @Test
    fun `true black is pure black, and only in night`() {
        assertEquals(ReaderPalette.BLACK, ReaderPalette.background(ReaderTheme.NIGHT, trueBlack = true))
        assertEquals(ReaderPalette.NIGHT_BG, ReaderPalette.background(ReaderTheme.NIGHT, trueBlack = false))
        // True black is an OLED trick for the dark theme; it must not blacken a paper page.
        assertEquals(ReaderPalette.PAPER_BG, ReaderPalette.background(ReaderTheme.PAPER, trueBlack = true))
    }

    @Test
    fun `true black softens the text rather than maximising contrast`() {
        val onBlack = ReaderPalette.foreground(ReaderTheme.NIGHT, trueBlack = true)!! and 0xFF
        val onNear = ReaderPalette.foreground(ReaderTheme.NIGHT, trueBlack = false)!! and 0xFF
        assertTrue("full-strength text on pure black is a harsh edge in a dark room", onBlack < onNear)
    }

    // --- Volume keys -------------------------------------------------------------------------

    @Test
    fun `volume keys do nothing until they are turned on`() {
        val off = ReaderSettings(volumeKeyTurns = false)
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = true, off))
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = false, off))
    }

    @Test
    fun `down is forward`() {
        val on = ReaderSettings(volumeKeyTurns = true)
        assertEquals(VolumeKeys.Action.NEXT_PAGE, VolumeKeys.action(volumeUp = false, on))
        assertEquals(VolumeKeys.Action.PREVIOUS_PAGE, VolumeKeys.action(volumeUp = true, on))
    }

    @Test
    fun `reversing swaps them and nothing else`() {
        val flipped = ReaderSettings(volumeKeyTurns = true, volumeKeysReversed = true)
        assertEquals(VolumeKeys.Action.PREVIOUS_PAGE, VolumeKeys.action(volumeUp = false, flipped))
        assertEquals(VolumeKeys.Action.NEXT_PAGE, VolumeKeys.action(volumeUp = true, flipped))
    }

    @Test
    fun `reversing while disabled still does nothing`() {
        val s = ReaderSettings(volumeKeyTurns = false, volumeKeysReversed = true)
        assertEquals(VolumeKeys.Action.IGNORE, VolumeKeys.action(volumeUp = true, s))
    }
}
