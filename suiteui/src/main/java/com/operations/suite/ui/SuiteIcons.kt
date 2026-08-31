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
     * [appId]'s mark drawn in [colours] rather than tinted — LifeOps' dial in its launcher's purple
     * and amber, or any app in the pair chosen for it in the sandbox settings.
     *
     * The colours are fitted to the backdrop first ([SuiteColors.fitForMode]), because a palette
     * written for a dark launcher background is still being asked to read on a light wallpaper. The
     * fit moves brightness only, so the hues the app is recognised by survive it.
     */
    fun inColoursForApp(appId: AppId, colours: SuiteIconColors, dark: Boolean): ImageVector? =
        SuiteGlyphs.inColour(
            SuiteApps.of(appId).iconKey,
            SuiteIconColors(
                line = SuiteColors.fitForMode(colours.line, dark),
                highlight = SuiteColors.fitForMode(colours.highlight, dark)
            )
        )
}
