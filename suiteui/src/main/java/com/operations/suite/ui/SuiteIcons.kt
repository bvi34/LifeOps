package com.operations.suite.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.ui.graphics.vector.ImageVector
import com.operations.backupkit.AppId
import com.operations.suitekit.SuiteApps

/**
 * The glyph each hosted app is known by. :suitekit names the icon ("monitor-heart") because it is
 * Android-free; this is the one place those names become drawables, so a new app is a line here and
 * an entry in [SuiteApps].
 */
object SuiteIcons {

    fun forKey(iconKey: String): ImageVector = when (iconKey) {
        "dashboard" -> Icons.Filled.Dashboard
        "menu-book" -> Icons.AutoMirrored.Filled.MenuBook
        "inventory" -> Icons.Filled.Inventory2
        "psychology" -> Icons.Filled.Psychology
        "monitor-heart" -> Icons.Filled.MonitorHeart
        "groups" -> Icons.Filled.Groups
        "account-tree" -> Icons.Filled.AccountTree
        else -> Icons.Filled.Home
    }

    fun forApp(appId: AppId): ImageVector = forKey(SuiteApps.of(appId).iconKey)
}
