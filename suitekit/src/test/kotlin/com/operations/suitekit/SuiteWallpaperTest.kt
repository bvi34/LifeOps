package com.operations.suitekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteWallpaperTest {

    private val scheme = SuiteThemes.scheme(SuitePreset.DEFAULT, dark = true)

    @Test
    fun `every shipped design resolves to something paintable and readable`() {
        WallpaperDesign.entries.forEach { design ->
            val spec = SuiteWallpapers.spec(SuiteWallpaper(design = design), scheme)
            assertEquals("$design must paint an opaque field", 0xFF, SuiteColors.alpha(spec.base))
            assertTrue(
                "$design must write in one of the suite's two inks",
                spec.ink == SuiteColors.INK || spec.ink == SuiteColors.SNOW
            )
            spec.layers.forEach { layer ->
                assertTrue("$design has a layer with nothing in it", layer.stops.isNotEmpty())
                layer.stops.forEach { stop ->
                    assertTrue("$design has a stop off the gradient", stop.position in 0f..1f)
                }
            }
        }
    }

    @Test
    fun `a light design gets dark ink and a dark one gets light ink`() {
        assertEquals(SuiteColors.INK, SuiteWallpapers.spec(SuiteWallpaper(design = WallpaperDesign.PAPER), scheme).ink)
        assertEquals(SuiteColors.SNOW, SuiteWallpapers.spec(SuiteWallpaper(design = WallpaperDesign.MIDNIGHT), scheme).ink)
    }

    @Test
    fun `dimming a pale wallpaper far enough flips its text to light`() {
        val paper = SuiteWallpaper(design = WallpaperDesign.PAPER)
        assertEquals(SuiteColors.INK, SuiteWallpapers.spec(paper, scheme).ink)

        val dimmed = SuiteWallpapers.spec(paper.copy(dim = SuiteWallpaper.MAX_DIM), scheme)
        assertEquals(SuiteColors.SNOW, dimmed.ink)
        assertTrue("the veil must actually be drawn", SuiteColors.alpha(dimmed.veil) > 0)
    }

    @Test
    fun `an undimmed wallpaper draws no veil at all`() {
        val spec = SuiteWallpapers.spec(SuiteWallpaper(design = WallpaperDesign.TIDE), scheme)
        assertEquals(0, SuiteColors.alpha(spec.veil))
    }

    @Test
    fun `a dim past the cap is held at the cap rather than painting the screen black`() {
        val absurd = SuiteWallpapers.spec(SuiteWallpaper(design = WallpaperDesign.TIDE, dim = 4f), scheme)
        val capped = SuiteWallpapers.spec(
            SuiteWallpaper(design = WallpaperDesign.TIDE, dim = SuiteWallpaper.MAX_DIM), scheme
        )
        assertEquals(capped.veil, absurd.veil)
        assertTrue(SuiteColors.alpha(absurd.veil) < 0xFF)
    }

    @Test
    fun `the theme design follows the suite's preset, and a fixed design ignores it`() {
        val ocean = SuiteThemes.scheme(SuitePreset.OCEAN, dark = true)
        val themed = SuiteWallpaper(design = WallpaperDesign.THEME)
        assertEquals(scheme.background, SuiteWallpapers.spec(themed, scheme).base)
        assertEquals(ocean.background, SuiteWallpapers.spec(themed, ocean).base)

        // A design chosen on purpose is a decision, not a filter: changing the preset leaves it be.
        val ember = SuiteWallpaper(design = WallpaperDesign.EMBER)
        assertEquals(SuiteWallpapers.spec(ember, scheme), SuiteWallpapers.spec(ember, ocean))
    }

    @Test
    fun `the custom design paints the user's own colours in the style they chose`() {
        val custom = SuiteWallpaper(
            design = WallpaperDesign.CUSTOM,
            style = WallpaperStyle.LINEAR,
            angle = WallpaperAngle.LEFT_RIGHT,
            startColor = "#102030",
            endColor = "#405060"
        )
        val spec = SuiteWallpapers.spec(custom, scheme)
        assertEquals(0xFF102030L, spec.base)

        val layer = spec.layers.single()
        assertEquals(WallpaperShape.LINEAR, layer.shape)
        assertEquals(listOf(0xFF102030L, 0xFF405060L), layer.stops.map { it.color })
        assertEquals(WallpaperAngle.LEFT_RIGHT.startX, layer.startX, 0f)
        assertEquals(WallpaperAngle.LEFT_RIGHT.endX, layer.endX, 0f)
    }

    @Test
    fun `a solid custom wallpaper is one flat field with nothing drawn over it`() {
        val spec = SuiteWallpapers.spec(
            SuiteWallpaper(design = WallpaperDesign.CUSTOM, style = WallpaperStyle.SOLID, startColor = "#1B1F24"),
            scheme
        )
        assertEquals(0xFF1B1F24L, spec.base)
        assertTrue(spec.layers.isEmpty())
    }

    @Test
    fun `a half-typed custom colour falls back instead of blanking the screen`() {
        val spec = SuiteWallpapers.spec(
            SuiteWallpaper(design = WallpaperDesign.CUSTOM, style = WallpaperStyle.SOLID, startColor = "#12"),
            scheme
        )
        assertEquals(0xFF, SuiteColors.alpha(spec.base))
        assertEquals(0xFF3B1F5EL, spec.base)
    }

    @Test
    fun `the glows of an aurora sit at different places, or it would be one blob`() {
        val spec = SuiteWallpapers.spec(SuiteWallpaper(design = WallpaperDesign.AURORA), scheme)
        assertTrue(spec.layers.size > 1)
        assertTrue(spec.layers.all { it.shape == WallpaperShape.RADIAL })
        assertEquals(spec.layers.size, spec.layers.map { it.centerX to it.centerY }.toSet().size)
        // A glow that reached the rim at full strength would be a repaint, not a light.
        assertTrue(spec.layers.all { SuiteColors.alpha(it.stops.last().color) == 0 })
    }

    @Test
    fun `the picker previews a design without disturbing what is stored`() {
        val stored = SuiteWallpaper(design = WallpaperDesign.CUSTOM, startColor = "#123456")
        val preview = SuiteWallpapers.preview(WallpaperDesign.FOREST, stored, scheme)
        assertEquals(SuiteWallpapers.spec(stored.copy(design = WallpaperDesign.FOREST), scheme), preview)
        // The Custom card still previews the colours the user typed.
        assertEquals(
            0xFF123456L,
            SuiteWallpapers.preview(WallpaperDesign.CUSTOM, stored, scheme).base
        )
    }

    @Test
    fun `an unknown design name degrades to the theme's own wallpaper`() {
        assertEquals(WallpaperDesign.THEME, WallpaperDesign.from("SANDSTORM"))
        assertEquals(WallpaperDesign.THEME, WallpaperDesign.from(null))
        assertEquals(WallpaperAngle.TOP_BOTTOM, WallpaperAngle.from("SIDEWAYS"))
    }

    @Test
    fun `the wallpaper rides along in the appearance document`() {
        val appearance = SuiteAppearance(
            wallpaper = SuiteWallpaper(
                design = WallpaperDesign.NEBULA,
                style = WallpaperStyle.AURORA,
                angle = WallpaperAngle.DIAGONAL_UP,
                startColor = "#0C0A1A",
                dim = 0.25f
            )
        )
        assertEquals(appearance, SuiteAppearanceCodec.fromJson(SuiteAppearanceCodec.toJson(appearance)))
    }

    @Test
    fun `a document written before wallpapers existed keeps the theme's own backdrop`() {
        val decoded = SuiteAppearanceCodec.fromJson("""{"preset":"OCEAN","darkMode":false}""")!!
        assertEquals(SuiteWallpaper(), decoded.wallpaper)
        assertEquals(WallpaperDesign.THEME, decoded.wallpaper.design)
    }

    @Test
    fun `a wallpaper naming a design or style this build does not know degrades to the defaults`() {
        val decoded = SuiteAppearanceCodec.fromJson(
            """{"wallpaper":{"design":"SANDSTORM","style":"PLAID","dim":9.0}}"""
        )!!
        assertEquals(WallpaperDesign.THEME, decoded.wallpaper.design)
        assertEquals(WallpaperStyle.LINEAR, decoded.wallpaper.style)
        assertEquals(SuiteWallpaper.MAX_DIM, decoded.wallpaper.dim, 0f)
    }

    @Test
    fun `choosing a wallpaper leaves the rest of the appearance alone`() {
        val appearance = SuiteAppearance(preset = SuitePreset.SUNSET, appAccentsEnabled = false)
        val repainted = appearance.withWallpaper { it.copy(design = WallpaperDesign.GRAPHITE) }
        assertEquals(WallpaperDesign.GRAPHITE, repainted.wallpaper.design)
        assertEquals(appearance.copy(wallpaper = repainted.wallpaper), repainted)
        assertNotEquals(appearance.wallpaper, repainted.wallpaper)
    }
}
