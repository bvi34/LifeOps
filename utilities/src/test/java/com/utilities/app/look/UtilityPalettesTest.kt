package com.utilities.app.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour maths, which is the part of an appearance feature that can be wrong without looking
 * wrong to whoever wrote it.
 *
 * Two properties carry most of the weight. **Warming must not dim**: the whole argument for cutting
 * blue out of each colour rather than laying an orange sheet over the surface is that contrast
 * survives it, and that is an assertion rather than a claim. **An accent must be legible on the
 * surface it lands on**, including surfaces nobody anticipated, because the household can type in
 * any two colours they like.
 */
class UtilityPalettesTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `system has no colours of its own and takes the app's`() {
        val look = UtilityLook(theme = UtilityTheme.SYSTEM)
        assertNull(UtilityPalettes.surface(look.theme, look.trueBlack))
        val palette = UtilityPalettes.resolve(look, fallbackSurface = white, fallbackText = black)
        assertEquals(white, palette.surface)
        assertEquals(black, palette.text)
    }

    @Test
    fun `every preset answers with both a surface and something readable on it`() {
        UtilityTheme.entries.filter { it != UtilityTheme.SYSTEM }.forEach { theme ->
            val surface = UtilityPalettes.surface(theme, trueBlack = false)!!
            val text = UtilityPalettes.text(theme, trueBlack = false)!!
            assertTrue(
                "$theme is not readable: ${UtilityPalettes.contrast(surface, text)}",
                UtilityPalettes.contrast(surface, text) >= 7f
            )
        }
    }

    @Test
    fun `true black is only true under night`() {
        assertEquals(UtilityPalettes.BLACK, UtilityPalettes.surface(UtilityTheme.NIGHT, trueBlack = true))
        assertEquals(
            UtilityPalettes.PAPER_SURFACE,
            UtilityPalettes.surface(UtilityTheme.PAPER, trueBlack = true)
        )
        // Not pure white on pure black: that is what causes halation.
        assertNotEquals(white, UtilityPalettes.text(UtilityTheme.NIGHT, trueBlack = true))
    }

    @Test
    fun `a colour somebody picked does not repaint a preset`() {
        val picked = 0xFF00FF00.toInt()
        assertEquals(
            UtilityPalettes.SEPIA_SURFACE,
            UtilityPalettes.surface(UtilityTheme.SEPIA, trueBlack = false, custom = picked)
        )
        assertEquals(
            picked,
            UtilityPalettes.surface(UtilityTheme.CUSTOM, trueBlack = false, custom = picked)
        )
    }

    @Test
    fun `warming cuts blue, leaves red, and does not dim the page`() {
        val warmed = UtilityPalettes.warm(white, 1f)
        assertEquals("red is untouched", 0xFF, (warmed ushr 16) and 0xFF)
        assertTrue("blue drops most", (warmed and 0xFF) < ((warmed ushr 8) and 0xFF))
        assertTrue("green drops a little", ((warmed ushr 8) and 0xFF) < 0xFF)

        // The property the whole approach exists for: a warmed surface is still as readable.
        val plain = UtilityPalettes.contrast(white, black)
        val warm = UtilityPalettes.contrast(warmed, UtilityPalettes.warm(black, 1f))
        assertTrue("warming must not cost contrast: $plain -> $warm", warm >= plain * 0.75f)
    }

    @Test
    fun `no warmth changes nothing at all`() {
        assertEquals(white, UtilityPalettes.warm(white, 0f))
        assertEquals(white, UtilityPalettes.warm(white, -1f))
    }

    @Test
    fun `an accent that cannot be read on the surface is moved until it can`() {
        // Pale yellow on white: exactly the send button that disappears.
        val invisible = 0xFFFFF7B0.toInt()
        val rescued = UtilityPalettes.readable(invisible, on = white, fallback = black)
        assertTrue(
            "still unreadable at ${UtilityPalettes.contrast(rescued, white)}",
            UtilityPalettes.contrast(rescued, white) >= UtilityPalettes.MIN_CONTRAST
        )
    }

    @Test
    fun `an accent that is already legible is left exactly as it was`() {
        val strong = 0xFF1D4ED8.toInt()
        assertEquals(strong, UtilityPalettes.readable(strong, on = white, fallback = black))
    }

    @Test
    fun `a resolved palette is legible on any two colours somebody types in`() {
        // The real test of the rescue: every surface in a sweep, with an accent chosen to be
        // awkward on about half of them.
        listOf(white, black, 0xFF808080.toInt(), 0xFF203020.toInt(), 0xFFFFF0C0.toInt()).forEach { surface ->
            val look = UtilityLook(
                theme = UtilityTheme.CUSTOM,
                surfaceColor = surface,
                textColor = UtilityPalettes.contrastOn(surface),
                accentColor = 0xFF9AA0A6.toInt()
            )
            val palette = UtilityPalettes.resolve(look, white, black)
            assertTrue(
                "accent unreadable on ${Integer.toHexString(surface)}",
                UtilityPalettes.contrast(palette.accent, palette.surface) >= UtilityPalettes.MIN_CONTRAST
            )
            // The large-text threshold rather than the body one: a mid-grey surface cannot reach
            // 4.5 against anything, and refusing to render it would be the wrong answer to
            // somebody who deliberately chose mid-grey.
            assertTrue(
                "text unreadable on ${Integer.toHexString(surface)}",
                UtilityPalettes.contrast(palette.text, palette.surface) >= UtilityPalettes.MIN_CONTRAST
            )
        }
    }

    @Test
    fun `muted text is between the surface and the text, and the divider is nearer the surface`() {
        val palette = UtilityPalettes.resolve(UtilityLook(), white, black)
        val toMuted = UtilityPalettes.contrast(palette.muted, palette.surface)
        val toText = UtilityPalettes.contrast(palette.text, palette.surface)
        assertTrue(toMuted in 1.5f..toText)
        assertTrue(UtilityPalettes.contrast(palette.line, palette.surface) < toMuted)
    }

    @Test
    fun `a surface somebody made transparent is made opaque again`() {
        // A part-transparent surface lets whatever is behind it through, which is never what
        // choosing a colour meant.
        val clean = UtilityLook(theme = UtilityTheme.CUSTOM, surfaceColor = 0x40FF0000).sanitized()
        assertEquals(0xFF, (clean.surfaceColor!! ushr 24) and 0xFF)
        assertEquals(0xFF0000, clean.surfaceColor!! and 0xFFFFFF)
    }

    @Test
    fun `every slider is clamped to something that can be drawn`() {
        val wild = UtilityLook(warmth = 9f, textScale = 40f, cornerDp = -8f, paddingDp = 400f).sanitized()
        assertEquals(1f, wild.warmth, 0f)
        assertEquals(2.0f, wild.textScale, 0f)
        assertEquals(0f, wild.cornerDp, 0f)
        assertEquals(12f, wild.paddingDp, 0f)
    }

    @Test
    fun `dark is decided by the surface, not by the theme`() {
        assertTrue(UtilityPalettes.resolve(UtilityLook(theme = UtilityTheme.NIGHT), white, black).dark)
        assertTrue(!UtilityPalettes.resolve(UtilityLook(theme = UtilityTheme.PAPER), black, white).dark)
    }
}
