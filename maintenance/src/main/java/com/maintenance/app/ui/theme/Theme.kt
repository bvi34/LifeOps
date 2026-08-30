package com.maintenance.app.ui.theme

import androidx.compose.runtime.Composable
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteTheme

/**
 * This app's theme is the *suite's* theme, wearing this app's colour.
 *
 * Naming [AppId.MAINTENANCE] is the whole of this file's job: the sandbox answers with the shared
 * preset and mode, tinted with whatever colour this app is currently set to (see
 * `com.operations.suitekit.SuiteThemes`). Change it in the sandbox's gear, and this app repaints
 * with the rest of the suite.
 */
@Composable
fun MaintenanceTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.MAINTENANCE, content = content)
}
