package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteAppearanceTest {

    @Test
    fun `the appearance document round-trips through the codec`() {
        val appearance = SuiteAppearance(
            preset = SuitePreset.SUNSET,
            darkMode = false,
            palette = SuitePalette(primary = "#123456"),
            appAccentsEnabled = false,
            accents = mapOf(AppId.HEALTH.key to "#2C7A7B"),
            sandboxWins = false,
            iconPaints = mapOf(AppId.HEALTH.key to SuiteIconPaint("#112233", "#445566"))
        )
        assertEquals(appearance, SuiteAppearanceCodec.fromJson(SuiteAppearanceCodec.toJson(appearance)))
    }

    @Test
    fun `a malformed or empty document parses to null instead of throwing`() {
        assertNull(SuiteAppearanceCodec.fromJson("not json"))
        assertNull(SuiteAppearanceCodec.fromJson(""))
        assertNull(SuiteAppearanceCodec.fromJson(null))
    }

    @Test
    fun `a partial document keeps the defaults for what it does not say`() {
        val decoded = SuiteAppearanceCodec.fromJson("""{"darkMode":false}""")!!
        assertEquals(SuitePreset.DEFAULT, decoded.preset)
        assertEquals(SuitePalette(), decoded.palette)
        assertEquals(emptyMap<String, String>(), decoded.accents)
        assertEquals(emptyMap<String, SuiteIconPaint>(), decoded.iconPaints)
        assertTrue(decoded.appAccentsEnabled)
        assertTrue("a document written before the switch existed keeps the sandbox winning", decoded.sandboxWins)
        assertEquals(false, decoded.darkMode)
    }

    @Test
    fun `an unknown preset name degrades to the default rather than crashing an app`() {
        val decoded = SuiteAppearanceCodec.fromJson("""{"preset":"AURORA","palette":{}}""")!!
        assertEquals(SuitePreset.DEFAULT, decoded.preset)
        assertEquals(SuitePalette(), decoded.palette)
    }

    @Test
    fun `accents are keyed by app key, so renaming or reordering apps cannot lose one`() {
        val appearance = SuiteAppearance().withAccent(AppId.LOGISTICS, "#123456")
        assertEquals("#123456", appearance.accentHex(AppId.LOGISTICS))
        assertEquals(0xFF123456L, appearance.accentFor(AppId.LOGISTICS))
        assertTrue(SuiteAppearanceCodec.toJson(appearance).contains("\"${AppId.LOGISTICS.key}\""))

        // Apps with no override keep their shipped identity.
        assertEquals(SuiteColors.toHex(SuiteApps.of(AppId.HEALTH).defaultAccent), appearance.accentHex(AppId.HEALTH))
    }

    @Test
    fun `turning identity tints off silences every accent`() {
        val appearance = SuiteAppearance(appAccentsEnabled = false).withAccent(AppId.HEALTH, "#FF0000")
        assertNull(appearance.accentFor(AppId.HEALTH))
        // The choice is remembered, just not applied — flipping the switch back restores it.
        assertEquals("#FF0000", appearance.accentHex(AppId.HEALTH))
        assertEquals(0xFFFF0000L, appearance.copy(appAccentsEnabled = true).accentFor(AppId.HEALTH))
    }

    @Test
    fun `an app the user repainted is told apart from one still wearing what it shipped with`() {
        // The distinction decides whose colours a tile is drawn in: an app's own icon colours are
        // its own only until the user picks something else for it.
        assertFalse(SuiteAppearance().hasCustomAccent(AppId.LIFEOPS))

        val repainted = SuiteAppearance().withAccent(AppId.LIFEOPS, "#123456")
        assertTrue(repainted.hasCustomAccent(AppId.LIFEOPS))
        assertFalse("one app's choice is not another's", repainted.hasCustomAccent(AppId.HEALTH))

        assertFalse(repainted.withDefaultAccent(AppId.LIFEOPS).hasCustomAccent(AppId.LIFEOPS))
    }

    @Test
    fun `a mark wears icon colours only when its app has some`() {
        val shipped = SuiteApps.of(AppId.LIFEOPS).iconColors
        assertNotNull(shipped)
        assertEquals(shipped, SuiteAppearance().iconColorsFor(AppId.LIFEOPS))
        // Health ships none, so its mark is tinted with its accent instead.
        assertNull(SuiteAppearance().iconColorsFor(AppId.HEALTH))
    }

    @Test
    fun `sandbox-wins settles the one conflict - an app that shipped colours and was repainted`() {
        val repainted = SuiteAppearance().withAccent(AppId.LIFEOPS, "#123456")
        assertNull("the sandbox wins by default", repainted.iconColorsFor(AppId.LIFEOPS))
        assertEquals(
            SuiteApps.of(AppId.LIFEOPS).iconColors,
            repainted.copy(sandboxWins = false).iconColorsFor(AppId.LIFEOPS)
        )
        // With no colour chosen for it there is no conflict, so the flag decides nothing.
        assertEquals(
            SuiteApps.of(AppId.LIFEOPS).iconColors,
            SuiteAppearance(sandboxWins = true).iconColorsFor(AppId.LIFEOPS)
        )
    }

    @Test
    fun `any app can be given icon colours here, and those win either way`() {
        val painted = SuiteAppearance()
            .withAccent(AppId.HEALTH, "#123456")
            .withIconPaint(AppId.HEALTH, SuiteIconPaint(line = "#112233", highlight = "#445566"))
        val chosen = SuiteIconColors(line = 0xFF112233L, highlight = 0xFF445566L)
        assertEquals(chosen, painted.iconColorsFor(AppId.HEALTH))
        // Colours chosen here *are* the sandbox's choice, so the tie-breaker never overrules them.
        assertEquals(chosen, painted.copy(sandboxWins = false).iconColorsFor(AppId.HEALTH))

        // Switched off, the mark is tinted again — and the pair is remembered, not forgotten.
        val off = painted.withIconPaint(AppId.HEALTH, painted.iconPaintFor(AppId.HEALTH).copy(enabled = false))
        assertNull(off.iconColorsFor(AppId.HEALTH))
        assertEquals("#112233", off.iconPaintFor(AppId.HEALTH).line)

        // Dropped altogether, the app is back to what it ships — nothing, for Health.
        assertNull(painted.withoutIconPaint(AppId.HEALTH).iconColorsFor(AppId.HEALTH))
        assertFalse(painted.withoutIconPaint(AppId.HEALTH).hasCustomIconColors(AppId.HEALTH))
    }

    @Test
    fun `an app with no icon colours of its own starts from the suite's, and a blank field stays there`() {
        val appearance = SuiteAppearance()
        val scheme = SuiteThemes.scheme(appearance, AppId.HEALTH)
        val seed = appearance.defaultIconColors(AppId.HEALTH)
        assertEquals(scheme.secondary, seed.line)
        assertEquals(scheme.tertiary, seed.highlight)

        // Half-typed hex is not a blanked mark: the missing half falls back to that seed.
        val blank = appearance.withIconPaint(AppId.HEALTH, SuiteIconPaint(line = "", highlight = "#445566"))
        assertEquals(seed.line, blank.iconColorsFor(AppId.HEALTH)!!.line)
        assertEquals(0xFF445566L, blank.iconColorsFor(AppId.HEALTH)!!.highlight)

        // LifeOps starts from what it ships instead of from the scheme.
        assertEquals(SuiteApps.of(AppId.LIFEOPS).iconColors, appearance.defaultIconColors(AppId.LIFEOPS))
    }

    @Test
    fun `the sandbox shell itself has no accent`() {
        assertNull(SuiteAppearance().accentFor(null))
    }
}
