@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.HubCard

/**
 * The History hub: a landing page for the backward-looking record screens.
 * Growth sits at the top, followed by Reports and Resources.
 */
@Composable
fun HistoryScreen(
    onOpenGrowth: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenResources: () -> Unit,
    onOpenWellness: () -> Unit,
) {
    Scaffold(topBar = { AppHeader() }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HubCard(
                    title = "Growth",
                    subtitle = "Your permanent ring record, week over week",
                    icon = Icons.Default.TrackChanges,
                    onClick = onOpenGrowth
                )
            }
            item {
                HubCard(
                    title = "Reports",
                    subtitle = "Trends and breakdowns over a range",
                    icon = Icons.Default.BarChart,
                    onClick = onOpenReports
                )
            }
            item {
                HubCard(
                    title = "Wellness",
                    subtitle = "Energy, sensory load, and sleep — day by day",
                    icon = Icons.Default.Favorite,
                    onClick = onOpenWellness
                )
            }
            item {
                HubCard(
                    title = "Resources",
                    subtitle = "Aspect earnings and game resource slots",
                    icon = Icons.Default.Diamond,
                    onClick = onOpenResources
                )
            }
        }
    }
}
