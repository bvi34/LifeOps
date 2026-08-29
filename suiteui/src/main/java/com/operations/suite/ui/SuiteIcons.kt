package com.operations.suite.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteApps

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
}
