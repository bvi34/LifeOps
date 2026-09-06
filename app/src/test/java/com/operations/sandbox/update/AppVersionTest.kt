package com.operations.sandbox.update

import com.operations.sandbox.update.logic.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison the whole updater rests on.
 *
 * If this is wrong the app either never offers an update that exists or repeatedly offers one that
 * doesn't, and both look like a broken app rather than a broken comparison — so the awkward cases
 * are pinned here rather than left to a hand-check against one release.
 */
class AppVersionTest {

    @Test
    fun `parses a plain semver tag`() {
        val version = AppVersion.parse("1.4.2")
        assertEquals(AppVersion(1, 4, 2), version)
    }

    @Test
    fun `tolerates the v that tags conventionally carry`() {
        assertEquals(AppVersion.parse("1.4.2"), AppVersion.parse("v1.4.2"))
        assertEquals(AppVersion.parse("1.4.2"), AppVersion.parse("V1.4.2"))
        assertEquals(AppVersion.parse("1.4.2"), AppVersion.parse("  v1.4.2  "))
    }

    @Test
    fun `fills in the parts a short tag leaves out`() {
        assertEquals(AppVersion(2, 0, 0), AppVersion.parse("v2"))
        assertEquals(AppVersion(2, 3, 0), AppVersion.parse("v2.3"))
    }

    @Test
    fun `compares numerically, not as strings`() {
        // The case a string comparison gets backwards, and the reason this class exists.
        assertTrue(AppVersion.parse("1.10.0") > AppVersion.parse("1.9.9"))
        assertTrue(AppVersion.parse("2.0.0") > AppVersion.parse("1.99.99"))
        assertTrue(AppVersion.parse("1.0.10") > AppVersion.parse("1.0.9"))
    }

    @Test
    fun `a pre-release sorts below the release it precedes`() {
        assertTrue(AppVersion.parse("1.2.0-beta.1") < AppVersion.parse("1.2.0"))
        assertTrue(AppVersion.parse("1.2.0-rc.1") > AppVersion.parse("1.2.0-beta.9"))
        assertTrue(AppVersion.parse("1.2.0-beta.2") > AppVersion.parse("1.2.0-beta.1"))
        // A shorter run of identifiers sorts below a longer one sharing its prefix.
        assertTrue(AppVersion.parse("1.2.0-beta") < AppVersion.parse("1.2.0-beta.1"))
        // Numeric identifiers sort below alphanumeric ones.
        assertTrue(AppVersion.parse("1.2.0-1") < AppVersion.parse("1.2.0-alpha"))
    }

    @Test
    fun `build metadata is ignored entirely`() {
        assertEquals(AppVersion.parse("1.2.3"), AppVersion.parse("1.2.3+build.77"))
        assertEquals(AppVersion.parse("1.2.3-rc.1"), AppVersion.parse("1.2.3-rc.1+abc1234"))
    }

    @Test
    fun `anything unparseable is UNKNOWN and loses to everything`() {
        val nonsense = listOf(null, "", "   ", "latest", "1.x.3", "-1.2.3", "1.2.3.4", "v")
        nonsense.forEach { raw ->
            assertEquals("expected UNKNOWN for ${raw ?: "null"}", AppVersion.UNKNOWN, AppVersion.parse(raw))
        }
        assertTrue(AppVersion.UNKNOWN < AppVersion.parse("0.0.1"))
    }

    @Test
    fun `a developer build is below every real release`() {
        // What BuildConfig.VERSION_NAME says when no tag was passed to Gradle. It must lose to any
        // published release, or a locally-built APK would never be offered an upgrade.
        val dev = AppVersion.parse("0.0.0-dev")
        assertTrue(dev < AppVersion.parse("0.0.1"))
        assertTrue(dev < AppVersion.parse("1.0.0"))
        assertTrue(dev.isPreRelease)
    }

    @Test
    fun `equal versions are not upgrades in either direction`() {
        val a = AppVersion.parse("v1.4.2")
        val b = AppVersion.parse("1.4.2")
        assertEquals(0, a.compareTo(b))
        assertFalse(a > b)
        assertFalse(b > a)
    }

    @Test
    fun `renders back to the tag it came from`() {
        assertEquals("1.4.2", AppVersion.parse("v1.4.2").toString())
        assertEquals("1.2.0-beta.1", AppVersion.parse("v1.2.0-beta.1").toString())
    }
}
