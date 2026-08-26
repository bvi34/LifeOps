package com.lifeops.app.ui.theme

import androidx.compose.runtime.Composable
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteTheme

/**
 * LifeOps' theme is the *suite's* theme, wearing LifeOps' colour.
 *
 * The presets this file used to hold — Default, Beacon, Ocean, Sunset and the custom-palette
 * derivation — were the best-developed look in the suite, so they were promoted rather than
 * replaced: they now live in `com.operations.suitekit.SuiteThemes`, value for value, and every
 * hosted app renders them. The Operations Sandbox's gear is where they are chosen; LifeOps'
 * Appearance card edits the very same setting.
 *
 * What LifeOps keeps is its identity colour, applied on top of the shared look like every other
 * app's (see `com.operations.suitekit.SuiteApps`).
 */
@Composable
fun LifeOpsTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.LIFEOPS, content = content)
}
