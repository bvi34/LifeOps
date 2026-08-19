@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DISPLAY_FMT = DateTimeFormatter.ofPattern("h:mm a")

/**
 * A button showing a time ("5:00 PM") that opens a clock-face dial to pick it — no typing.
 * [minutes] is minutes-from-midnight (local); [is24Hour] defaults to the device's own
 * 12h/24h setting so the dial matches how the rest of the phone shows time.
 */
@Composable
fun TimePickerButton(
    label: String,
    minutes: Int,
    onMinutesSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    Column(modifier = modifier) {
        OutlinedButton(onClick = { showPicker = true }) {
            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(LocalTime.of(minutes / 60, minutes % 60).format(DISPLAY_FMT))
        }
        if (isError) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = is24Hour
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onMinutesSelected(state.hour * 60 + state.minute)
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }
}

/** minutes-from-midnight → "5:00 PM" for read-only display. */
fun formatClock12h(minutes: Int): String =
    LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59)).format(DISPLAY_FMT)
