package com.operations.suite.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteColors
import com.operations.suitekit.SuiteIconColors

/**
 * The glyph each hosted app is known by. :suitekit names the icon ("thermometer") because it is
 * Android-free; this is the one place those names become drawables, so a new app is an entry in
 * [SuiteApps] plus a mark in [SuiteGlyphs].
 *
 * The marks are the suite's own (see [SuiteGlyphs]) rather than stock Material icons. The fallback
 * still is a stock icon, deliberately: an app that reached the home screen without anyone drawing
 * it a mark should look like the oversight it is, not like one of its neighbours.
 */
object SuiteIcons {

    fun forKey(iconKey: String): ImageVector = SuiteGlyphs.byKey[iconKey] ?: Icons.Filled.Home

    fun forApp(appId: AppId): ImageVector = forKey(SuiteApps.of(appId).iconKey)

    /**
     * The mark [appId] paints *itself* with — LifeOps' dial in its launcher's own purple and
     * amber — or null for the apps whose mark is a single colour, which is all the others. Those
     * get [forApp] and the caller's tint.
     *
     * The declared colours are fitted to the backdrop first ([SuiteColors.fitForMode]), because an
     * icon palette written for a dark launcher background is still being asked to read on a light
     * wallpaper. The fit moves brightness only, so the hues the app is recognised by survive it.
     */
    fun ownColoursForApp(appId: AppId, dark: Boolean): ImageVector? {
        val info = SuiteApps.of(appId)
        val colours = info.iconColors ?: return null
        return SuiteGlyphs.inColour(
            info.iconKey,
            SuiteIconColors(
                line = SuiteColors.fitForMode(colours.line, dark),
                highlight = SuiteColors.fitForMode(colours.highlight, dark)
            )
        )
    }
}
