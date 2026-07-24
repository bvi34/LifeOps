@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.wellness

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/** A 1–10 rating strip (matches the week self-rating control). Null = nothing chosen yet. */
@Composable
fun RatingRow(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            (1..10).forEach { n ->
                val isSelected = value == n
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onSelect(n) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            n.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/** Daytime check-in pop-up: energy 1–10, sensory 1–10, and an optional "why". */
@Composable
fun CheckInDialog(
    onSubmit: (energy: Int, sensory: Int, why: String) -> Unit,
    onDismiss: () -> Unit
) {
    var energy by remember { mutableStateOf<Int?>(null) }
    var sensory by remember { mutableStateOf<Int?>(null) }
    var why by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RatingRow("Energy (1 low → 10 high)", energy) { energy = it }
                RatingRow("Sensory load (1 calm → 10 overloaded)", sensory) { sensory = it }
                OutlinedTextField(
                    value = why,
                    onValueChange = { why = it },
                    label = { Text("Why? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(energy!!, sensory!!, why) },
                enabled = energy != null && sensory != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}

/**
 * Morning sleep pop-up (first open after 5am): a pre-filled, editable sleep duration plus tired 1–10,
 * energy 1–10, and an optional "why". [estimatedMinutes] pre-fills the duration. When [reconstruction]
 * is present the night was reconstructed from tracked screen/charging events (the accurate path) and a
 * bedtime → wake summary is shown; otherwise the value is a coarser screen-time estimate, and when
 * [hasUsageAccess] is false even that is blank and [onGrantAccess] offers the Settings shortcut.
 */
@Composable
fun SleepCheckInDialog(
    estimatedMinutes: Int?,
    hasUsageAccess: Boolean,
    reconstruction: com.lifeops.app.util.SleepInferenceService.SleepReconstruction?,
    onGrantAccess: () -> Unit,
    onSubmit: (energy: Int, tired: Int, sleepMinutes: Int?, why: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Editable "hours.decimal" string, pre-filled from the estimate (e.g. 440 min -> "7.3").
    var hoursText by remember {
        mutableStateOf(estimatedMinutes?.let { String.format("%.1f", it / 60.0) } ?: "")
    }
    var tired by remember { mutableStateOf<Int?>(null) }
    var energy by remember { mutableStateOf<Int?>(null) }
    var why by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Good morning") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (reconstruction != null) {
                    Text(
                        buildString {
                            append("Bed ")
                            append(formatClock(reconstruction.bedtimeMillis))
                            append(" → up ")
                            append(formatClock(reconstruction.wakeMillis))
                            if (reconstruction.interruptions > 0) {
                                append(" · ")
                                append(reconstruction.interruptions)
                                append(if (reconstruction.interruptions == 1) " interruption" else " interruptions")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    when {
                        reconstruction != null -> "Reconstructed from your device activity. Adjust if it's off."
                        hasUsageAccess && estimatedMinutes != null -> "Estimated from your last phone use. Adjust if it's off."
                        else -> "How long did you sleep?"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Hours slept") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (reconstruction == null && !hasUsageAccess) {
                    TextButton(
                        onClick = onGrantAccess,
                        modifier = Modifier.padding(top = 0.dp)
                    ) { Text("Grant usage access to auto-estimate") }
                }
                RatingRow("Tired (1 rested → 10 exhausted)", tired) { tired = it }
                RatingRow("Energy (1 low → 10 high)", energy) { energy = it }
                OutlinedTextField(
                    value = why,
                    onValueChange = { why = it },
                    label = { Text("Why? (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = hoursText.toDoubleOrNull()?.let { (it * 60).toInt().coerceIn(0, 16 * 60) }
                    onSubmit(energy!!, tired!!, mins, why)
                },
                enabled = energy != null && tired != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}

/** Epoch millis → a short local clock time, e.g. "11:15 PM". */
private fun formatClock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
