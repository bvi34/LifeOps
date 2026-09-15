@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.util.TaskWeatherFit

/**
 * The self-contained blocks of the task detail body: the read-only banner a closed week puts
 * at the top, and the weather, counter and people sections.
 *
 * Each takes what it needs and renders it — none of them reads the body's hoisted state, which is
 * exactly what made them separable first.
 */

@Composable
internal fun ReadOnlyBanner(weekLabel: String?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                weekLabel?.let { "Read-only · $it" } ?: "Read-only",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun rememberAttachmentBitmap(data: String): ImageBitmap? =
    remember(data) {
        runCatching {
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }

@Composable
internal fun WeatherSection(fit: TaskWeatherFit?, requirement: TaskWeatherRequirement) {
    Text("Weather", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        weatherRequirementSummary(requirement),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    if (fit != null) {
        Spacer(Modifier.height(6.dp))
        val goodToday = fit.suitableToday || fit.bestIsToday
        val headline = when {
            goodToday -> "☀ Good today · ${fit.todayMatchPercent}% match"
            fit.bestWindowLabel != null -> "🌧 Not today · better on ${fit.bestWindowLabel}"
            else -> "🌧 No good weather window this week"
        }
        Text(
            headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (goodToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
        )
        fit.notTodayReason?.takeIf { !goodToday }?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
internal fun CounterSection(
    counter: Counter,
    weeklyTotal: Int,
    editable: Boolean,
    onLog: () -> Unit,
    onOpen: () -> Unit
) {
    Text("Counter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(counter.name, style = MaterialTheme.typography.bodyLarge)
            if (editable) {
                Text(
                    "$weeklyTotal this week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        if (editable) {
            FilledTonalButton(onClick = onLog, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Log")
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Open counter",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
internal fun PeopleSection(
    involved: List<Person>,
    all: List<Person>,
    editable: Boolean,
    onAttach: (String) -> Unit,
    onDetach: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val available = all.filter { p -> involved.none { it.id == p.id } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("People", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (editable) {
            Box {
                TextButton(onClick = { pickerOpen = true }, enabled = available.isNotEmpty()) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    available.forEach { person ->
                        DropdownMenuItem(
                            text = { Text(person.name) },
                            onClick = { onAttach(person.id); pickerOpen = false }
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (involved.isEmpty()) {
        Text(
            "No one assigned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            involved.forEach { person ->
                InputChip(
                    selected = false,
                    onClick = { onOpen(person.id) },
                    label = { Text(person.name) },
                    trailingIcon = if (editable) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${person.name}",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onDetach(person.id) }
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/** Compact one-line summary of a task's weather constraints, e.g. "Outdoor · ≤85° · no rain". */
private fun weatherRequirementSummary(req: TaskWeatherRequirement): String = buildList {
    if (req.outdoorPreferred) add("Outdoor")
    req.maxTempF?.let { add("≤${it}°") }
    req.minTempF?.let { add("≥${it}°") }
    if (req.avoidRain) add("no rain")
    req.maxWindMph?.let { add("wind ≤${it}mph") }
    req.durationMinutes?.let { add("${it}m") }
}.joinToString(" · ").ifEmpty { "Weather-sensitive" }

@Composable
internal fun StatusChip(status: TaskStatus) {
    val (color, label) = when (status) {
        TaskStatus.PENDING -> MaterialTheme.colorScheme.primary to "Pending"
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary to "Done"
        TaskStatus.SKIPPED -> MaterialTheme.colorScheme.outline to "Skipped"
        TaskStatus.INCOMPLETE -> MaterialTheme.colorScheme.error to "Incomplete"
        TaskStatus.EXPIRED -> MaterialTheme.colorScheme.error to "Expired"
        TaskStatus.CARRIED_FORWARD -> MaterialTheme.colorScheme.secondary to "Carried"
        TaskStatus.UNSUCCESSFUL -> MaterialTheme.colorScheme.outline to "Unsuccessful"
        TaskStatus.QUEUED -> MaterialTheme.colorScheme.secondary to "Queued"
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
