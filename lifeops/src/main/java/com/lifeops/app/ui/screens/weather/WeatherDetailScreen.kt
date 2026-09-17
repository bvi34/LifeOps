@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.WeatherDetail

/**
 * The full weather read for one location — what the weather screen's "Today's conditions" card
 * opens.
 *
 * That card answers one question in one line ("Good day to be outside · Best window: Saturday").
 * This screen is the rest of it: the next day hour by hour, the week day by day, every reading the
 * feed carries, the full text of any active alert, and the outdoor score broken into the reasons it
 * came out the way it did. All of it comes from the same cached report the card was built from, so
 * opening it costs nothing and works offline; refresh is the only thing here that touches the
 * network, and only when asked.
 */
@Composable
fun WeatherDetailScreen(
    viewModel: WeatherDetailViewModel,
    onOpenRadar: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        val report = state.report
        val detail = state.detail
        if (report == null || detail == null) {
            EmptyDetail(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DetailHeaderCard(
                    report = report,
                    assessment = state.assessment,
                    placeName = state.location?.displayName(state.followsDevice)
                        ?: report.location.name.ifBlank { "This location" },
                    onOpenRadar = onOpenRadar
                )
            }

            detail.rainArrival?.let { item { RainCalloutCard(it) } }

            items(report.alerts.sortedByDescending { it.severity.rank }, key = { it.id }) { alert ->
                AlertDetailCard(alert)
            }
            items(state.advisories) { advisory -> AdvisoryDetailCard(advisory) }

            if (detail.hours.isNotEmpty()) {
                item {
                    DetailSectionHeader(
                        "Next ${detail.hours.size} hours",
                        detail.temperatureHighF?.let { high ->
                            detail.temperatureLowF?.let { low -> "$high° at the warmest, $low° at the coolest" }
                        }
                    )
                }
                item { HourlyStrip(detail.hours, detail.temperatureLowF, detail.temperatureHighF) }
            }

            if (detail.metrics.isNotEmpty()) {
                item { DetailSectionHeader("Conditions") }
                item { MetricGrid(detail.metrics) }
            }

            if (detail.days.isNotEmpty()) {
                // One scale for every bar, so the days are comparable rather than each self-relative.
                val lows = detail.days.mapNotNull { it.lowF ?: it.highF }
                val highs = detail.days.mapNotNull { it.highF ?: it.lowF }
                val weekLow = lows.minOrNull() ?: 0
                val weekHigh = highs.maxOrNull() ?: weekLow
                item { DetailSectionHeader("The week ahead", "Tap a day for the full forecast") }
                items(detail.days) { day -> DailyRow(day, weekLow, weekHigh) }
            }

            state.assessment?.let { assessment ->
                item { OutdoorBreakdownCard(assessment) }
            }

            if (state.outdoorWindows.isNotEmpty()) {
                item { DetailSectionHeader("Best time to be outside", "Weather only — no tasks, no calendar") }
                items(state.outdoorWindows) { window -> BestWindowRow(window) }
            }

            item {
                Text(
                    "Forecast from the US National Weather Service. " +
                        "Hourly runs ${WeatherDetail.DEFAULT_HOURS} hours out; the daily product runs about a week.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun EmptyDetail(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No saved weather yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Refresh to fetch conditions for this location.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}
