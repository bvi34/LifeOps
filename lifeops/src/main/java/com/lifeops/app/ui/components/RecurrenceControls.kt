@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * Recurrence checkbox plus, when checked, a cadence picker shared by the create and edit task
 * dialogs. Cadence is surfaced as two values that map straight onto TaskEntity:
 *  • [intervalWeeks] with [dayOfMonth] == null  → week-interval (weekly / bi-weekly / every N weeks)
 *  • [dayOfMonth] non-null                       → monthly-by-date (interval ignored)
 *
 * The presets cover the common cadences; "Monthly (by date)" reveals a day-of-month field.
 */
private val WEEK_PRESETS = listOf(1 to "Weekly", 2 to "Every 2 weeks", 3 to "Every 3 weeks", 4 to "Every 4 weeks")

@Composable
fun RecurrenceControls(
    isRecurring: Boolean,
    intervalWeeks: Int,
    dayOfMonth: Int?,
    onChange: (isRecurring: Boolean, intervalWeeks: Int, dayOfMonth: Int?) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isRecurring,
                onCheckedChange = { checked ->
                    // Turning it on defaults to weekly; turning it off clears the cadence.
                    if (checked) onChange(true, if (intervalWeeks < 1) 1 else intervalWeeks, dayOfMonth)
                    else onChange(false, intervalWeeks, dayOfMonth)
                }
            )
            Text("Repeats", style = MaterialTheme.typography.bodyMedium)
        }

        if (isRecurring) {
            val isMonthly = dayOfMonth != null
            val currentLabel = if (isMonthly) "Monthly (by date)"
                else WEEK_PRESETS.firstOrNull { it.first == intervalWeeks }?.second ?: "Every $intervalWeeks weeks"

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Cadence") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    WEEK_PRESETS.forEach { (weeks, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onChange(true, weeks, null)
                                expanded = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Monthly (by date)") },
                        onClick = {
                            onChange(true, intervalWeeks, dayOfMonth ?: 1)
                            expanded = false
                        }
                    )
                }
            }

            if (isMonthly) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = dayOfMonth?.toString() ?: "",
                        onValueChange = { raw ->
                            val day = raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 31)
                            onChange(true, intervalWeeks, day ?: 1)
                        },
                        label = { Text("Day") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        "of each month (29–31 land on the last day in shorter months)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
