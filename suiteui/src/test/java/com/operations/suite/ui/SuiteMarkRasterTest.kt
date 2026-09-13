package com.operations.suite.ui

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of [SuiteMarkRaster] that is data rather than drawing.
 *
 * A bitmap is hard to assert about and easy to assert about badly ("it has pixels in it"). What is
 * worth pinning is what the renderer *reads*, because that is where it can silently be wrong: a
 * mark whose paths it does not find is a mark it draws as nothing, and a mark wrapped in a group
 * transform is a mark it draws in the wrong place. Both are invisible from inside the app — the
 * home screen uses Compose's own renderer and looks perfect — and only show up on the phone's
 * launcher, which is exactly where nobody is looking.
 *
 * Runs on the JVM, like `SuiteGlyphsTest`, and for the same reason: an `ImageVector` is data until
 * something draws it.
 */
class SuiteMarkRasterTest {

    @Test
    fun `every app's mark has paths for the rasteriser to draw`() {
        AppId.entries.forEach { appId ->
            val paths = SuiteMarkRaster.pathsOf(SuiteIcons.forApp(appId))
            assertTrue("$appId has no paths at all; its shortcut icon would be a blank square", paths.isNotEmpty())
            assertTrue(
                "$appId has a path with no nodes in it",
                paths.all { it.pathData.isNotEmpty() }
            )
            assertTrue(
                "$appId has a path that is neither stroked nor filled, so nothing would be drawn for it",
                paths.all { it.stroke != null || it.fill != null }
            )
        }
    }

    @Test
    fun `no mark hides inside a group transform the rasteriser would ignore`() {
        // The renderer flattens groups and drops their transforms, which is correct only while no
        // mark uses one. When this fails, the fix is to teach the renderer about group transforms —
        // not to delete the assertion, and not to leave the mark looking right in the app and wrong
        // on the launcher.
        AppId.entries.forEach { appId ->
            assertFalse(
                "$appId's mark uses a group transform; SuiteMarkRaster.draw would ignore it",
                SuiteMarkRaster.hasTransformedGroup(SuiteIcons.forApp(appId))
            )
        }
    }

    @Test
    fun `no two apps rasterise to the same drawing`() {
        // The same property `SuiteGlyphsTest` holds for the marks themselves, asserted again on
        // what the rasteriser actually walks: a shortcut menu where two apps look alike is a
        // shortcut menu that has stopped being useful, and flattening is where two marks could
        // become the same without either drawing changing.
        val drawn = AppId.entries.associateWith { appId ->
            SuiteMarkRaster.pathsOf(SuiteIcons.forApp(appId)).map { it.pathData.toString() }
        }
        assertEquals(
            "two apps rasterise to the same geometry: ${drawn.entries.groupBy { it.value }
                .filterValues { it.size > 1 }
                .values.map { group -> group.map { it.key } }}",
            AppId.entries.size,
            drawn.values.distinct().size
        )
    }
}
