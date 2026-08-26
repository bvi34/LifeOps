package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteThemesTest {

    @Test
    fun `without an accent every app renders the identical shared scheme`() {
        val appearance = SuiteAppearance(preset = SuitePreset.OCEAN, darkMode = true, appAccentsEnabled = false)
        val schemes = AppId.entries.map { SuiteThemes.scheme(appearance, it) }.toSet()
        assertEquals(1, schemes.size)
        assertEquals(SuiteThemes.scheme(appearance, null), schemes.first())
    }

    @Test
    fun `an accent repaints the primary roles but keeps the shared look`() {
        val appearance = SuiteAppearance(preset = SuitePreset.OCEAN, darkMode = true)
        val shared = SuiteThemes.scheme(appearance, null)
        val health = SuiteThemes.scheme(appearance, AppId.HEALTH)

        assertNotEquals(shared.primary, health.primary)
        // The suite's tertiary is the thread back to the shared look; an accent never touches it.
        assertEquals(shared.tertiary, health.tertiary)
        // Surfaces are washed with the accent, not repainted in it.
        assertNotEquals(shared.surface, health.surface)
        assertTrue(
            "the wash must stay closer to the preset than to the accent",
            distance(health.surface, shared.surface) < distance(health.surface, SuiteApps.of(AppId.HEALTH).defaultAccent)
        )
    }

    @Test
    fun `each app reads as itself`() {
        val appearance = SuiteAppearance(preset = SuitePreset.DEFAULT, darkMode = true)
        val primaries = AppId.entries.map { SuiteThemes.scheme(appearance, it).primary }
        assertEquals(AppId.entries.size, primaries.toSet().size)
    }

    @Test
    fun `an accent chosen in the sandbox beats the app's shipped colour`() {
        val repainted = SuiteAppearance().withAccent(AppId.CITATION, "#FF0000")
        val scheme = SuiteThemes.scheme(repainted, AppId.CITATION)
        assertEquals(SuiteColors.fitForMode(0xFFFF0000L, dark = true), scheme.primary)

        val reverted = repainted.withDefaultAccent(AppId.CITATION)
        assertEquals(
            SuiteThemes.scheme(SuiteAppearance(), AppId.CITATION).primary,
            SuiteThemes.scheme(reverted, AppId.CITATION).primary
        )
    }

    @Test
    fun `every slot is opaque and readable in both modes`() {
        for (preset in SuitePreset.entries) {
            for (dark in listOf(true, false)) {
                for (appId in AppId.entries) {
                    val appearance = SuiteAppearance(preset = preset, darkMode = dark)
                    val s = SuiteThemes.scheme(appearance, appId)
                    val label = "$preset/${if (dark) "dark" else "light"}/${appId.key}"
                    listOf(
                        s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer,
                        s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
                        s.tertiary, s.onTertiary, s.background, s.onBackground, s.surface,
                        s.onSurface, s.surfaceVariant, s.onSurfaceVariant, s.outline, s.error, s.onError
                    ).forEach { assertEquals("$label must be opaque", 0xFF, SuiteColors.alpha(it)) }

                    assertTrue("$label primary must contrast its surface", readable(s.primary, s.surface))
                    assertTrue("$label text must contrast its surface", readable(s.onSurface, s.surface))
                    assertTrue("$label body text must contrast the background", readable(s.onBackground, s.background))
                    assertTrue("$label onPrimary must contrast primary", readable(s.onPrimary, s.primary))
                }
            }
        }
    }

    @Test
    fun `the custom preset renders the palette the user typed`() {
        val palette = SuitePalette(
            primary = "#FF8800",
            secondary = "#00AAFF",
            tertiary = "#00FF88",
            darkBackground = "#101010",
            lightBackground = "#FAFAFA"
        )
        val dark = SuiteThemes.scheme(SuitePreset.CUSTOM, dark = true, palette = palette)
        assertEquals(0xFFFF8800L, dark.primary)
        assertEquals(0xFF00AAFFL, dark.secondary)
        assertEquals(0xFF00FF88L, dark.tertiary)
        assertEquals(0xFF101010L, dark.background)

        val light = SuiteThemes.scheme(SuitePreset.CUSTOM, dark = false, palette = palette)
        assertEquals(0xFFFAFAFAL, light.background)
        assertEquals(0xFFFFFFFFL, light.surface)
    }

    @Test
    fun `a garbled custom palette still produces a usable scheme`() {
        val palette = SuitePalette(primary = "###", secondary = "", tertiary = "nope", darkBackground = "x")
        val scheme = SuiteThemes.scheme(SuitePreset.CUSTOM, dark = true, palette = palette)
        assertEquals(SuiteColors.FALLBACK, scheme.primary)
        assertEquals(0xFF121212L, scheme.background)
        assertTrue(readable(scheme.onSurface, scheme.surface))
    }

    private fun readable(a: Long, b: Long): Boolean =
        kotlin.math.abs(SuiteColors.luminance(a) - SuiteColors.luminance(b)) > 0.12f

    private fun distance(a: Long, b: Long): Int =
        kotlin.math.abs(SuiteColors.red(a) - SuiteColors.red(b)) +
            kotlin.math.abs(SuiteColors.green(a) - SuiteColors.green(b)) +
            kotlin.math.abs(SuiteColors.blue(a) - SuiteColors.blue(b))
}
