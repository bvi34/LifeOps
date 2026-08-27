package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Episode
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.TempTrend
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.ui.common.*

/**
 * One illness as a card, and the summary block inside it — how long, how high it peaked, which way it
 * is going, what was given, what is still going. The reading-back half of an episode; the
 * hour-by-hour half is `EpisodeHistory`.
 */

@Composable
internal fun EpisodeCard(
    episode: Episode,
    expanded: Boolean,
    summary: EpisodeSummary?,
    historyCount: Int,
    unit: TempUnit,
    onToggle: () -> Unit,
    onHistory: () -> Unit,
    onEditDates: () -> Unit,
    onEnd: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (episode.isOpen) "Open since ${formatStamp(episode.startedAt)}"
                        else "${formatDay(episode.startedAt)} – ${formatDay(episode.endedAt!!)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (episode.isOpen) {
                    AssistChip(onClick = {}, label = { Text("Open") })
                }
            }

            if (expanded && summary != null) {
                EpisodeSummaryBlock(summary, unit)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggle) { Text(if (expanded) "Hide" else "Summary") }
                TextButton(onClick = onHistory) {
                    Text(if (expanded && historyCount > 0) "History ($historyCount)" else "History")
                }
                TextButton(onClick = onEditDates) { Text("Dates") }
                if (episode.isOpen) {
                    TextButton(onClick = onEnd) { Text("Mark over") }
                } else {
                    TextButton(onClick = onReopen) { Text("Reopen") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** The episode read back: headline, the numbers, and the observations only the span can make. */
@Composable
private fun EpisodeSummaryBlock(summary: EpisodeSummary, unit: TempUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary.headline, style = MaterialTheme.typography.bodyMedium)
        CareBadge(summary.careLevel)

        val facts = buildList {
            add("${summary.readingCount} reading(s)")
            summary.peak?.let { add("peak ${Temperature.format(it.celsius, unit)}") }
            if (summary.feverRunHours > 0) add("fever running ${summary.feverRunHours}h")
            if (summary.trend != TempTrend.UNKNOWN) add(summary.trend.label.lowercase())
            if (summary.doseCount > 0) add("${summary.doseCount} dose(s)")
        }
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)

        if (summary.activeSymptoms.isNotEmpty()) {
            Text(
                "Still going: ${summary.activeSymptoms.joinToString { "${it.name} (${it.severity})" }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (summary.resolvedSymptoms.isNotEmpty()) {
            Text(
                "Passed: ${summary.resolvedSymptoms.joinToString { it.name }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        summary.advice.forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = careColor(summary.careLevel))
        }
    }
}
