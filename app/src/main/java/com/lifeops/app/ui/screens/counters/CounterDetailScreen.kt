@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.counters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.model.CounterEvent
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.util.DateUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EVENT_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy  ·  HH:mm")
private val WEEK_DAY_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatInstant(iso: String): String = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(EVENT_TIME_FMT)
} catch (_: Exception) { iso }

private fun formatWeek(weekKey: Int): String =
    "Week of " + DateUtil.weekStartForIndex(weekKey).format(WEEK_DAY_FMT)

@Composable
fun CounterDetailScreen(
    viewModel: CounterDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    state.counter?.name ?: "Counter",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatColumn("This week", state.weekCount, Modifier.weight(1f))
                        StatColumn("All time", state.totalCount, Modifier.weight(1f))
                        Button(onClick = { viewModel.increment() }) { Text("+1") }
                    }
                }
            }

            item {
                Text("Weekly trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (state.trend.isEmpty()) {
                item { EmptyHint("No history yet.") }
            } else {
                // Newest week first for reading; the query returns oldest-first.
                items(state.trend.asReversed(), key = { it.weekKey }) { row -> TrendRow(row) }
            }

            item {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (state.events.isEmpty()) {
                item { EmptyHint("No entries yet.") }
            } else {
                items(state.events, key = { it.id }) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("$value", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun TrendRow(row: CounterWeeklyTotal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatWeek(row.weekKey), style = MaterialTheme.typography.bodyMedium)
        Text("${row.total}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EventRow(event: CounterEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(formatInstant(event.occurredAt), style = MaterialTheme.typography.bodyMedium)
            if (!event.note.isNullOrBlank()) {
                Text(event.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Text("+${event.delta}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}
