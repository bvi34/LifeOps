package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Episode
import com.health.app.logic.CareLevel
import com.health.app.logic.Timeline
import com.health.app.logic.TimelineDay
import com.health.app.logic.TimelineEntry
import com.health.app.logic.TimelineKind
import com.health.app.ui.common.*

/**
 * An illness read back as **what actually happened, and when** — the version a doctor asks for in a
 * waiting room and the version the person who was up all three nights cannot produce from memory.
 *
 * The ordering, the day numbering and the "written up later" marks are all `logic/Timeline`'s; this
 * file only draws them.
 */

/**
 * The illness, hour by hour: what was done and when.
 *
 * The other half of reading an episode back. The summary says *how it went*; this says *what
 * happened* — which is the version a doctor asks for, and the version the person who was up all
 * three nights cannot produce from memory.
 *
 * Days read newest first, and each day reads forwards, because that is how the two are actually
 * used: you want the latest day immediately, and then to read it the way it was lived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
    episode: Episode,
    days: List<TimelineDay>,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val total = Timeline.entryCount(days)
    val filledIn = Timeline.filledInCount(days)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (episode.isOpen) "Open since ${formatStamp(episode.startedAt)}"
                        else "${formatDay(episode.startedAt)} – ${formatDay(episode.endedAt!!)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        buildString {
                            append(if (total == 1) "1 record" else "$total records")
                            // Said plainly, because filling the history in is the encouraged thing
                            // to do — not a defect to apologise for.
                            if (filledIn > 0) append(" · $filledIn added afterwards")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "add") {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Text("Add something that happened")
                }
            }

            if (days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing was recorded during this one. Anything you remember can still go " +
                            "in — a dose, a temperature, the day the cough started — and it will be " +
                            "filed under this illness by the time you give it, not by today's date.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            days.forEach { day ->
                item(key = "day:${day.date}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            day.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            formatDay(day.entries.first().atMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(day.entries, key = { it.id }) { entry -> HistoryRow(entry) }
            }

            item(key = "disclaimer") { DisclaimerText(Modifier.padding(top = 12.dp)) }
        }
    }
}

/** One thing that happened: the time, what it was, and — when it applies — that it was remembered. */
@Composable
private fun HistoryRow(entry: TimelineEntry) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            formatTime(entry.atMillis),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.kind == TimelineKind.EPISODE) FontWeight.Bold else FontWeight.Normal,
                    color = entry.careLevel
                        ?.takeIf { it != CareLevel.ROUTINE }
                        ?.let { careColor(it) }
                        ?: MaterialTheme.colorScheme.onSurface
                )
                entry.careLevel?.let { CareBadge(it) }
            }
            listOfNotNull(entry.kindLabel(), entry.detail).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.filledInLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The kind, where saying it adds something.
 *
 * A dose row already says "Calpol, 5 mL" and a reading already says "38.4 °C (ear)"; prefixing those
 * with their own category is noise. A care note is the one that genuinely needs it — "rang the
 * surgery" reads very differently depending on whether it was filed as an appointment or a note.
 */
private fun TimelineEntry.kindLabel(): String? = when (kind) {
    TimelineKind.DOSE, TimelineKind.EPISODE, TimelineKind.READING -> null
    TimelineKind.SYMPTOM_STARTED -> TimelineKind.SYMPTOM_STARTED.label
    TimelineKind.SYMPTOM_ENDED -> null
    TimelineKind.CARE -> null
}
