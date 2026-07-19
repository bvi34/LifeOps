@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.HubCard

/**
 * The Planning hub: a landing page that drills into the forward-looking
 * management screens (projects, counters, runbooks, templates, cost resources).
 */
@Composable
fun PlanningScreen(
    onOpenFutureTasks: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenCounters: () -> Unit,
    onOpenRunbooks: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenCostResources: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenWeather: () -> Unit,
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
                    title = "Future Tasks",
                    subtitle = "Queued until their due date's week arrives",
                    icon = Icons.Default.Schedule,
                    onClick = onOpenFutureTasks
                )
            }
            item {
                HubCard(
                    title = "Projects",
                    subtitle = "Group related tasks into longer efforts",
                    icon = Icons.Default.AccountTree,
                    onClick = onOpenProjects
                )
            }
            item {
                HubCard(
                    title = "Counters",
                    subtitle = "Track recurring tallies over time",
                    icon = Icons.Default.Numbers,
                    onClick = onOpenCounters
                )
            }
            item {
                HubCard(
                    title = "People",
                    subtitle = "Household profiles, preferences & who's involved",
                    icon = Icons.Default.People,
                    onClick = onOpenPeople
                )
            }
            item {
                HubCard(
                    title = "Weather",
                    subtitle = "Conditions, alerts & the best time for outdoor tasks",
                    icon = Icons.Default.WbSunny,
                    onClick = onOpenWeather
                )
            }
            item {
                HubCard(
                    title = "Runbooks",
                    subtitle = "Reusable step lists stamped onto tasks",
                    icon = Icons.Default.MenuBook,
                    onClick = onOpenRunbooks
                )
            }
            item {
                HubCard(
                    title = "Templates",
                    subtitle = "Reusable task sets applied to a week",
                    icon = Icons.Default.ListAlt,
                    onClick = onOpenTemplates
                )
            }
            item {
                HubCard(
                    title = "Cost Resources",
                    subtitle = "External resources tracked per task",
                    icon = Icons.Default.Bolt,
                    onClick = onOpenCostResources
                )
            }
        }
    }
}
