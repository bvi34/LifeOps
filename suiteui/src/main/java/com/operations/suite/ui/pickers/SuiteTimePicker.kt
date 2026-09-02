@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.suite.ui.pickers

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
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

/**
 * The suite's time picker — the only one. A clock face, never a text field.
 *
 * Times travel as **minutes from midnight**, which is what the apps that schedule anything already
 * store, and what survives a backup without dragging a date along with it. The dial itself follows
 * the phone's own 12h/24h setting, so it matches the clock in the status bar rather than an opinion
 * held by one app.
 */

private val DISPLAY_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val DISPLAY_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** How the suite writes a time of day. Named, rather than a bare `formatClock`, because three apps
 *  already have a private function by that name and a silently-shadowed formatter is a clock that
 *  reads 17:00 in one row and 5:00 PM in the next. */
object SuiteClock {
    /** Minutes-from-midnight as a wall clock time, clamped so bad stored data still renders. */
    fun format(minutes: Int, is24Hour: Boolean = false): String =
        LocalTime.of((minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))
            .format(if (is24Hour) DISPLAY_24H else DISPLAY_12H)
}

/**
 * A formatter following the phone's own 12h/24h setting — for read-only times sitting next to a
 * picker, so the row and the dial agree about what "17:00" is called.
 */
@Composable
fun rememberClockFormatter(): (Int) -> String {
    val is24Hour = deviceIs24Hour()
    return remember(is24Hour) { { minutes: Int -> SuiteClock.format(minutes, is24Hour) } }
}

@Composable
private fun deviceIs24Hour(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}

/**
 * A button showing a time ("5:00 PM") that opens the dial to change it.
 *
 * [isError] turns [label] into the complaint underneath — that is how a caller says "this end time
 * is before its start" without moving the button or resizing the dialog around it.
 */
@Composable
fun SuiteTimeButton(
    label: String,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var picking by remember { mutableStateOf(false) }
    val is24Hour = deviceIs24Hour()

    Column(modifier = modifier) {
        OutlinedButton(onClick = { picking = true }) {
            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(SuiteClock.format(minutes, is24Hour))
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

    if (picking) {
        SuiteTimePickerDialog(
            title = label,
            minutes = minutes,
            onConfirm = { onMinutesChange(it); picking = false },
            onDismiss = { picking = false }
        )
    }
}

/** The dial on its own, for a screen that opens it from something other than a button. */
@Composable
fun SuiteTimePickerDialog(
    title: String,
    minutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = (minutes / 60).coerceIn(0, 23),
        initialMinute = (minutes % 60).coerceIn(0, 59),
        is24Hour = deviceIs24Hour()
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
