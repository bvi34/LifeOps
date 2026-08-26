package com.people.app.ui.theme

import androidx.compose.runtime.Composable
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteTheme

/**
 * This app's theme is the *suite's* theme, wearing this app's colour.
 *
 * The palette that used to live here — its own light/dark schemes, its own idea of a background —
 * moved to the Operations Sandbox, which now owns one look for every hosted app and one accent per
 * app inside it. Naming [AppId.PEOPLE] is the whole of this file's job: the sandbox answers with the
 * shared preset and mode, tinted with whatever colour this app is currently set to (see
 * `com.operations.suitekit.SuiteThemes`). Change it in the sandbox's gear, and this app repaints
 * with the rest of the suite.
 */
@Composable
fun PeopleTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.PEOPLE, content = content)
}
