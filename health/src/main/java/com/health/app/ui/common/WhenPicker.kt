package com.health.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.logic.DoseSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * *When did this happen?*
 *
 * Every record Health takes now asks it, because the answer is not always "now". Most of the time it
 * is, which is why "Now" is the default and one tap wide — nobody filling in a 3am temperature
 * should have to touch a date picker to say "3am, obviously". The rest of the row is the handful of
 * answers people actually give when it isn't now: an hour ago, this morning, last night.
 *
 * Anything the chips don't cover opens a real date and time picker. That path exists for the case
 * this whole feature is for — reconstructing a week of last month's flu — where the answer is a
 * specific date and no amount of relative shorthand helps.
 *
 * **The future is not offered.** Not by a chip, and not accepted from the picker: a temperature that
 * hasn't been taken yet is a typo, and Health's dose windows would take it seriously (see
 * `DoseSchedule`, which ignores future-dated doses precisely because they are data-entry slips).
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

/**
 * The when-field that sits in every record dialog.
 *
 * [value] is the chosen instant; [onValueChange] gets the new one. The caller keeps the state so the
 * dialog can save it, and so re-opening a dialog doesn't quietly reset a carefully chosen time.
 */
@Composable
fun WhenField(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "When"
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
            AssistChip(
                onClick = { showPicker = true },
                label = { Text("Pick a date") }
            )
        }

        Text(
            describeWhen(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showPicker) {
        WhenPickerDialog(
            initial = value,
            onDismiss = { showPicker = false },
            onConfirm = {
                onValueChange(it)
                showPicker = false
            }
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
    val stamp = formatStamp(millis, nowMillis)
    val gap = nowMillis - millis
    return when {
        gap < 60_000L -> "Now"
        gap > 0 -> "$stamp · ${DoseSchedule.formatAgo(gap)}"
        else -> stamp
    }
}

/**
 * A date and then a time, in two steps.
 *
 * Two dialogs rather than one crowded screen because that is the order the question is answered —
 * "which day was that?" then "roughly when?" — and because Material's date and time pickers are
 * separate components. The date step refuses days in the future outright, so the only way to end up
 * with one is a time later today, which the confirm button catches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhenPickerDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialDateTime = remember(initial) { Instant.ofEpochMilli(initial).atZone(zone) }

    var pickedDate by remember { mutableStateOf(initialDateTime.toLocalDate()) }
    var showTime by remember { mutableStateOf(false) }

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial,
        selectableDates = object : SelectableDates {
            // A record of something that hasn't happened yet is a typo, and the dose window would
            // take it seriously. Refuse it at the point of entry rather than validating it later.
            override fun isSelectableYear(year: Int): Boolean =
                year <= LocalDate.now().year

            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis <= System.currentTimeMillis()
        }
    )

    val timeState = rememberTimePickerState(
        initialHour = initialDateTime.hour,
        initialMinute = initialDateTime.minute,
        is24Hour = true
    )

    if (!showTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            // The picker works in UTC-midnight; the calendar date is what was meant.
                            pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        }
                        showTime = true
                    }
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        ) {
            DatePicker(state = dateState)
        }
        return
    }

    val chosen = remember(pickedDate, timeState.hour, timeState.minute) {
        pickedDate
            .atTime(LocalTime.of(timeState.hour, timeState.minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
    val inFuture = chosen > System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What time?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimePicker(state = timeState)
                if (inFuture) {
                    Text(
                        "That's still to come. Health records what has happened — pick a time that " +
                            "has already passed.",
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
