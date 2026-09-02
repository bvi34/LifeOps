package com.repository.app.ui.theme

import androidx.compose.runtime.Composable
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteTheme

/**
 * This app's theme is the *suite's* theme, wearing this app's colour.
 *
 * Naming [AppId.REPOSITORY] is the whole of this file's job; change the colour in the sandbox's gear
 * and this app repaints with the rest of the suite.
 */
@Composable
fun RepositoryTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.REPOSITORY, content = content)
}
