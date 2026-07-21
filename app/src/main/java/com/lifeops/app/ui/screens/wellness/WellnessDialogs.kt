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
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
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
 * Morning sleep pop-up (first open after 5am): the screen-time sleep estimate (editable), plus
 * tired 1–10, energy 1–10, and an optional "why". [estimatedMinutes] pre-fills the duration; when
 * [hasUsageAccess] is false the estimate is blank and [onGrantAccess] offers the Settings shortcut.
 */
@Composable
fun SleepCheckInDialog(
    estimatedMinutes: Int?,
    hasUsageAccess: Boolean,
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
                Text(
                    if (hasUsageAccess && estimatedMinutes != null)
                        "Estimated from your last phone use. Adjust if it's off."
                    else
                        "How long did you sleep?",
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
                if (!hasUsageAccess) {
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
