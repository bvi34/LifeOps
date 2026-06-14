@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalSardonicMessage = compositionLocalOf { "Surveying the damage..." }

@Composable
fun AppHeader(
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val message = LocalSardonicMessage.current
    TopAppBar(
        modifier = modifier,
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
        actions = actions,
    )
}
