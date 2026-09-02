@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.suite.ui.pickers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import android.text.format.DateFormat
import com.operations.suitekit.SuiteElapsed
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The suite's *when did this happen?* field — a date and a time together, as one instant.
 *
 * Most of the time the answer is "now", which is why "Now" is the default and one tap wide: nobody
 * filling in a 3am temperature should have to open a calendar to say "3am, obviously". The rest of
 * the chip row is the handful of answers people actually give when it isn't now — half an hour ago,
 * this morning, last night — and anything the chips don't cover falls through to the suite's date
 * and time pickers, in that order, because that is the order the question gets answered.
 *
 * [allowFuture] is off by default. A record of something that has not happened yet is a typo, and
 * the apps that reason over these instants (Health's dose windows, for one) would take it seriously.
 * Refuse it at the point of entry: the calendar will not offer a future day, and the time step will
 * not confirm one.
 */

/** One of the quick answers, as an offset back from now. */
private data class Shortcut(val label: String, val millisAgo: Long)

private val SHORTCUTS = listOf(
    Shortcut("Now", 0L),
    Shortcut("30m ago", 30L * 60 * 1000),
    Shortcut("1h ago", 60L * 60 * 1000),
    Shortcut("2h ago", 2 * 60L * 60 * 1000),
    Shortcut("4h ago", 4 * 60L * 60 * 1000),
    Shortcut("8h ago", 8 * 60L * 60 * 1000)
)

@Composable
fun SuiteWhenField(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "When",
    allowFuture: Boolean = false
) {
    var showPicker by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SHORTCUTS.forEach { shortcut ->
                // "Now" drifts by the time a dialog has been open for a minute, so a chip counts as
                // selected when the value is within a minute of what it would set — otherwise the
                // default chip un-highlights itself while somebody is typing, which reads as a bug.
                val target = now - shortcut.millisAgo
                FilterChip(
                    selected = kotlin.math.abs(value - target) < 60_000L,
                    onClick = { onValueChange(System.currentTimeMillis() - shortcut.millisAgo) },
                    label = { Text(shortcut.label) }
                )
            }
            AssistChip(onClick = { showPicker = true }, label = { Text("Pick a date") })
        }

        Text(
            describeWhen(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showPicker) {
        SuiteWhenPickerDialog(
            initial = value,
            allowFuture = allowFuture,
            onDismiss = { showPicker = false },
            onConfirm = { onValueChange(it); showPicker = false }
        )
    }
}

/**
 * "Yesterday 02:15 · 7h ago" — the chosen instant said twice, in the two ways people check it.
 *
 * Both, because they catch different mistakes: the clock time catches picking the wrong hour, and
 * the elapsed time catches picking the wrong *day*, which is the one you don't notice from "02:15".
 */
fun describeWhen(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val stamp = SuiteDates.formatStamp(millis, nowMillis)
    val gap = nowMillis - millis
    return when {
        gap < 60_000L -> "Now"
        gap > 0 -> "$stamp · ${SuiteElapsed.formatAgo(gap)}"
        else -> stamp
    }
}

/**
 * A date and then a time, in two steps — "which day was that?" then "roughly when?".
 *
 * Two dialogs rather than one crowded screen because that is the order the question is answered, and
 * because Material's date and time pickers are separate components. When the future is refused the
 * date step will not offer a later day at all, so the only way to reach one is a time later today —
 * which is what the confirm button here is watching for.
 */
@Composable
fun SuiteWhenPickerDialog(
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    allowFuture: Boolean = false
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialDateTime = remember(initial) { Instant.ofEpochMilli(initial).atZone(zone) }
    val context = LocalContext.current

    var pickedDate by remember { mutableStateOf(initialDateTime.toLocalDate()) }
    var showTime by remember { mutableStateOf(false) }

    // Held across both steps, not created with the second one: going Back to fix the day and coming
    // forward again should not quietly reset the time somebody has already dialled in.
    val timeState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    )

    if (!showTime) {
        SuiteDatePickerDialog(
            initial = pickedDate,
            onPick = { picked -> picked?.let { pickedDate = it }; showTime = true },
            onDismiss = onDismiss,
            confirmLabel = "Next",
            notAfter = if (allowFuture) null else LocalDate.now(zone)
        )
        return
    }

    val chosen = remember(pickedDate, timeState.hour, timeState.minute) {
        SuiteDates.toEpochMillis(pickedDate, LocalTime.of(timeState.hour, timeState.minute), zone)
    }
    val inFuture = !allowFuture && chosen > System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What time?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimePicker(state = timeState)
                if (inFuture) {
                    Text(
                        "That's still to come — pick a time that has already passed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !inFuture, onClick = { onConfirm(chosen) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = { showTime = false }) { Text("Back") } }
    )
}
