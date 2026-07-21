@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalSardonicMessage = compositionLocalOf { "Surveying the damage..." }

/** Provided once at the app root; AppHeader renders a global-search action that invokes it. */
val LocalGlobalSearch = compositionLocalOf<() -> Unit> { {} }

/** Standard back arrow for [AppHeader.navigationIcon] on drill-down screens. */
@Composable
fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

@Composable
fun AppHeader(
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    // Screens nested inside another Scaffold (e.g. the Week hub tabs) pass WindowInsets(0) so the
    // status-bar inset isn't applied a second time, which would open a gap above the bar.
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val message = LocalSardonicMessage.current
    val onGlobalSearch = LocalGlobalSearch.current
    TopAppBar(
        modifier = modifier,
        windowInsets = windowInsets,
        navigationIcon = navigationIcon,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifeOpsLogo(size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "LifeOps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onGlobalSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
            actions()
        },
    )
}
