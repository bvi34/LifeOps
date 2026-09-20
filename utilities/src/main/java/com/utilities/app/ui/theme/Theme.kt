package com.utilities.app.ui.theme

import androidx.compose.runtime.Composable
import com.operations.backupkit.AppId
import com.operations.suite.ui.SuiteTheme

/**
 * This app's theme is the *suite's* theme, wearing this app's colour.
 *
 * Worth one extra sentence here that the other eleven do not need: the screens inside Utilities follow
 * the suite like everything else, and the two surfaces it *takes over* — the keyboard, the message
 * thread — deliberately do not. Those have their own look, chosen on their own screens, because
 * they are not screens in this app: they are pieces of the phone, seen next to everybody else's
 * apps rather than next to Logistics. See [com.utilities.app.look.UtilityLook].
 */
@Composable
fun UtilitiesTheme(content: @Composable () -> Unit) {
    SuiteTheme(appId = AppId.UTILITIES, content = content)
}
