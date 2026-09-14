@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * When the app is allowed to interrupt you: the notification preferences, the reminder times,
 * and the sleep and wellness nudges.
 */

private fun hourLabel(hour: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h:00 $suffix"
}

@Composable
internal fun NotificationPreferenceRow(hour: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Default reminder time", style = MaterialTheme.typography.bodyMedium)
                Text(hourLabel(hour), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            TextButton(onClick = onClick) { Text("Change") }
        }
    }
}

@Composable
internal fun ReminderTimePickerDialog(
    currentHour: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hours = (5..22).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default reminder time") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())) {
                hours.forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = h == currentHour, onClick = { onSelect(h) })
                        Text(hourLabel(h), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
internal fun SleepTrackingSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sleep tracking", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled)
                            "Records screen & charging activity in the background to reconstruct your sleep. Shows an ongoing notification."
                        else
                            "Off — the morning report falls back to a rough screen-time estimate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
internal fun WellnessReminderSection(
    enabled: Boolean,
    slotHours: List<Int>,
    onToggle: (Boolean) -> Unit,
    onEditSlot: (Int) -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onAddSlot: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daytime & sleep prompts", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) "Better/worse + initiative check-ins plus the morning sleep report"
                        else "Turned off — no pop-ups or notifications",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                slotHours.forEachIndexed { index, hour ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(hourLabel(hour), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { onEditSlot(index) }) { Text("Change") }
                        if (slotHours.size > 1) {
                            IconButton(onClick = { onRemoveSlot(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (slotHours.size < 3) {
                    TextButton(onClick = onAddSlot) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add a time")
                    }
                }
            }
        }
    }
}

@Composable
internal fun HourPickerDialog(
    title: String,
    currentHour: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hours = (0..23).toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())) {
                hours.forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = h == currentHour, onClick = { onSelect(h) })
                        Text(hourLabel(h), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
