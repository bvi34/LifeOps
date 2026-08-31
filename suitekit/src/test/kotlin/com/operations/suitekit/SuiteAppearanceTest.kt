package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            accents = mapOf(AppId.HEALTH.key to "#2C7A7B")
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
        assertTrue(decoded.appAccentsEnabled)
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
    fun `the sandbox shell itself has no accent`() {
        assertNull(SuiteAppearance().accentFor(null))
    }
}
