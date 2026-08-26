package com.operations.sandbox.ui.theme

import androidx.compose.runtime.Composable
import com.operations.suite.ui.SuiteTheme

/**
 * The container shell's theme. It is the same [SuiteTheme] the hosted apps wear, with no app id —
 * the sandbox is the place the shared look is *chosen*, so it shows that look plain, without any
 * one app's accent tinting it. Every app's colour still appears here, on the home screen's tiles.
 */
@Composable
fun SandboxTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = null, content = content)
}
