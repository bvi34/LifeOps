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

    // --- Colours the reader chose --------------------------------------------------------------

    @Test
    fun `the custom theme uses the reader's own colours`() {
        val s = ReaderSettings(
            theme = ReaderTheme.CUSTOM,
            customBackground = 0xFF102030.toInt(),
            customText = 0xFFEEDDCC.toInt()
        )
        assertEquals(0xFF102030.toInt(), ReaderPalette.background(s.theme, s.trueBlack, s.customBackground))
        assertEquals(0xFFEEDDCC.toInt(), ReaderPalette.foreground(s.theme, s.trueBlack, s.customText))
        val (bg, fg) = ReaderPalette.of(s)!!
        assertEquals(0xFF102030.toInt(), bg)
        assertEquals(0xFFEEDDCC.toInt(), fg)
    }

    @Test
    fun `a custom theme with nothing chosen yet is a readable page rather than nothing`() {
        val (bg, fg) = ReaderPalette.of(ReaderSettings(theme = ReaderTheme.CUSTOM))!!
        assertEquals(ReaderPalette.CUSTOM_BG, bg)
        assertEquals(ReaderPalette.CUSTOM_FG, fg)
        assertTrue("the starting point must be legible", ReaderPalette.isLegible(fg, bg))
    }

    @Test
    fun `colours the reader chose are kept but ignored under the other themes`() {
        // Held across a switch so comparing against Sepia and coming back does not lose the work.
        val s = ReaderSettings(
            theme = ReaderTheme.SEPIA,
            customBackground = 0xFF102030.toInt(),
            customText = 0xFFEEDDCC.toInt()
        )
        val (bg, fg) = ReaderPalette.of(s)!!
        assertEquals(ReaderPalette.SEPIA_BG, bg)
        assertEquals(ReaderPalette.SEPIA_FG, fg)
        assertEquals(0xFF102030.toInt(), s.customBackground)
    }

    @Test
    fun `a part-transparent choice is made opaque rather than letting the app show through`() {
        val s = ReaderSettings(customBackground = 0x40FF0000, customText = 0x00112233).sanitized()
        assertEquals(0xFFFF0000.toInt(), s.customBackground)
        assertEquals(0xFF112233.toInt(), s.customText)
    }

    @Test
    fun `not having chosen a colour survives sanitizing`() {
        val s = ReaderSettings().sanitized()
        assertNull(s.customBackground)
        assertNull(s.customText)
    }

    @Test
    fun `the reader's colours warm with everything else`() {
        val s = ReaderSettings(
            theme = ReaderTheme.CUSTOM,
            customBackground = 0xFFFFFFFF.toInt(),
            customText = 0xFF3366CC.toInt(),
            warmth = 1f
        )
        val (bg, fg) = ReaderPalette.of(s)!!
        assertTrue("a warmed page loses blue like any other", (bg and 0xFF) < 0xFF)
        assertTrue((fg and 0xFF) < 0xCC)
    }

    // --- Hex codes ---------------------------------------------------------------------------

    @Test
    fun `hex codes are read in the forms people actually type`() {
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("#AABBCC"))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("aabbcc"))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("  #ABC  "))
        assertEquals(0xFFAABBCC.toInt(), ReaderPalette.parseHex("0xFFAABBCC"))
    }

    @Test
    fun `a hex code that is not one is refused rather than guessed at`() {
        assertNull(ReaderPalette.parseHex(""))
        assertNull(ReaderPalette.parseHex("#"))
        assertNull(ReaderPalette.parseHex("#AABB"))
        assertNull(ReaderPalette.parseHex("#GGHHII"))
        assertNull(ReaderPalette.parseHex("cornflower"))
    }

    @Test
    fun `an alpha-less code still comes back opaque`() {
        assertEquals(0xFF, (ReaderPalette.parseHex("#00AABBCC")!! ushr 24) and 0xFF)
        assertEquals(0xFF, (ReaderPalette.parseHex("000000")!! ushr 24) and 0xFF)
    }

    @Test
    fun `a colour round-trips through its hex code`() {
        listOf(ReaderPalette.PAPER_BG, ReaderPalette.SEPIA_FG, ReaderPalette.BLACK, 0xFFFFFFFF.toInt())
            .forEach { assertEquals(it, ReaderPalette.parseHex(ReaderPalette.hex(it))) }
    }

    // --- Contrast ----------------------------------------------------------------------------

    @Test
    fun `contrast runs from one for a colour on itself to twenty-one for black on white`() {
        assertEquals(1.0, ReaderPalette.contrast(ReaderPalette.PAPER_BG, ReaderPalette.PAPER_BG), 0.001)
        assertEquals(21.0, ReaderPalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `contrast does not care which colour is named first`() {
        val a = 0xFF2B2B2B.toInt()
        val b = 0xFFFBF7EF.toInt()
        assertEquals(ReaderPalette.contrast(a, b), ReaderPalette.contrast(b, a), 0.0001)
    }

    @Test
    fun `every shipped theme is legible, and a pair that is not gets called out`() {
        listOf(ReaderTheme.PAPER, ReaderTheme.SEPIA, ReaderTheme.NIGHT).forEach { theme ->
            listOf(true, false).forEach { trueBlack ->
                val bg = ReaderPalette.background(theme, trueBlack)!!
                val fg = ReaderPalette.foreground(theme, trueBlack)!!
                assertTrue("$theme (trueBlack=$trueBlack) must be readable", ReaderPalette.isLegible(fg, bg))
            }
        }
        // Grey on cream is the mistake the warning exists for.
        assertFalse(ReaderPalette.isLegible(0xFFBBBBBB.toInt(), ReaderPalette.PAPER_BG))
        assertFalse(ReaderPalette.isLegible(ReaderPalette.BLACK, ReaderPalette.BLACK))
    }

    @Test
    fun `luminance follows brightness rather than raw channel values`() {
        // Green reads far brighter than blue at the same channel value; a naive average would not
        // notice, and would wave through blue text on a green page.
        assertTrue(ReaderPalette.luminance(0xFF00FF00.toInt()) > ReaderPalette.luminance(0xFF0000FF.toInt()))
        assertTrue(ReaderPalette.luminance(0xFFFFFFFF.toInt()) > ReaderPalette.luminance(0xFF808080.toInt()))
        assertTrue(ReaderPalette.luminance(0xFF808080.toInt()) > ReaderPalette.luminance(ReaderPalette.BLACK))
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
