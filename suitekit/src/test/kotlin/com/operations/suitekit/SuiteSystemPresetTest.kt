package com.operations.suitekit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SuiteSystemPresetTest {

    private val wallpaper = SuiteSystemColors(
        primary = 0xFF4A6FA5L, secondary = 0xFF5B7C99L, tertiary = 0xFF8E6C9AL,
        background = 0xFF101418L, surface = 0xFF1C2024L, surfaceVariant = 0xFF2A2E33L
    )

    @After
    fun clearSource() {
        SuiteSystemPalette.source = null
    }

    @Test
    fun wallpaper_preset_is_seeded_from_the_system_colours() {
        val scheme = SuiteThemes.scheme(SuitePreset.SYSTEM, dark = true, system = wallpaper)
        assertEquals(wallpaper.primary, scheme.primary)
        assertEquals(wallpaper.tertiary, scheme.tertiary)
        assertEquals(wallpaper.background, scheme.background)
        assertEquals(wallpaper.surface, scheme.surface)
    }

    @Test
    fun without_system_colours_it_looks_like_default() {
        assertEquals(
            SuiteThemes.scheme(SuitePreset.DEFAULT, dark = false),
            SuiteThemes.scheme(SuitePreset.SYSTEM, dark = false)
        )
    }

    @Test
    fun installed_source_is_read_for_the_mode_asked() {
        var askedDark: Boolean? = null
        SuiteSystemPalette.source = { dark -> askedDark = dark; wallpaper }
        val scheme = SuiteThemes.scheme(SuiteAppearance(preset = SuitePreset.SYSTEM, darkMode = true), appId = null)
        assertEquals(true, askedDark)
        assertEquals(wallpaper.primary, scheme.primary)
    }

    @Test
    fun a_failing_source_falls_back_instead_of_breaking_the_theme() {
        SuiteSystemPalette.source = { error("no dynamic colour here") }
        assertEquals(
            SuiteThemes.scheme(SuitePreset.DEFAULT, dark = true),
            SuiteThemes.scheme(SuitePreset.SYSTEM, dark = true)
        )
    }

    @Test
    fun other_presets_never_consult_the_source() {
        SuiteSystemPalette.source = { error("must not be read") }
        assertNotEquals(0L, SuiteThemes.scheme(SuitePreset.OCEAN, dark = true).primary)
    }

    @Test
    fun the_preset_name_round_trips_and_unknown_names_still_fall_back() {
        assertEquals(SuitePreset.SYSTEM, SuitePreset.from("SYSTEM"))
        assertEquals(SuitePreset.DEFAULT, SuitePreset.from("NOT_A_PRESET"))
    }
}
