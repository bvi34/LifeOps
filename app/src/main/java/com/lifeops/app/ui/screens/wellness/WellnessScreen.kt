@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.wellness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.DateUtil

@Composable
fun WellnessScreen(
    viewModel: WellnessViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { TodayCard(state.today) }
            item { WeekCard(state.currentWeek) }

            if (state.weeklyTrend.size > 1) {
                item { SectionTitle("Weekly trend") }
                items(state.weeklyTrend.reversed()) { week -> WeekRow(week) }
            }

            if (state.recent.isNotEmpty()) {
                item { SectionTitle("Recent entries") }
                items(state.recent) { entry -> EntryRow(entry) }
            } else {
                item {
                    Text(
                        "No check-ins yet. The app will prompt you through the day and each morning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TodayCard(today: DailyWellness?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (today == null) {
                Text(
                    "Nothing logged yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }
            StatLine("Last night's sleep", formatSleep(today.sleepMinutes))
            StatLine("Tired on waking", today.sleepTired?.let { "$it / 10" } ?: "—")
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            StatLine("Check-ins", today.checkinCount.toString())
            StatLine("Avg energy", formatAvg(today.avgEnergy))
            StatLine("Avg sensory load", formatAvg(today.avgSensory))
        }
    }
}

@Composable
private fun WeekCard(week: WeeklyWellness?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (week == null) {
                Text(
                    "No entries this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }
            StatLine("Avg energy", formatAvg(week.avgEnergy))
            StatLine("Avg sensory load", formatAvg(week.avgSensory))
            StatLine("Avg tiredness", formatAvg(week.avgTired))
            StatLine("Avg sleep", formatSleep(week.avgSleepMinutes?.toInt()))
            StatLine("Check-ins / sleep logs", "${week.checkinCount} / ${week.sleepCount}")
        }
    }
}

@Composable
private fun WeekRow(week: WeeklyWellness) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Week of ${week.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "E ${formatAvg(week.avgEnergy)}  ·  S ${formatAvg(week.avgSensory)}  ·  ${formatSleep(week.avgSleepMinutes?.toInt())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun EntryRow(entry: WellnessCheckin) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (entry.kind == WellnessKind.SLEEP) "Sleep" else "Check-in",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    DateUtil.formatInstant(entry.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            val detail = if (entry.kind == WellnessKind.SLEEP) {
                buildList {
                    entry.sleepMinutes?.let { add("Sleep ${formatSleep(it)}") }
                    entry.tired?.let { add("Tired $it") }
                    entry.energy?.let { add("Energy $it") }
                }.joinToString("  ·  ")
            } else {
                buildList {
                    entry.energy?.let { add("Energy $it") }
                    entry.sensory?.let { add("Sensory $it") }
                }.joinToString("  ·  ")
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
            entry.note?.let {
                Text("\"$it\"", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatAvg(v: Double?): String = v?.let { String.format("%.1f", it) } ?: "—"

private fun formatSleep(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "—"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}
