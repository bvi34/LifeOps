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
import com.operations.suitekit.SuiteVerdict
import java.time.Instant
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
 * **What counts as an acceptable moment is the app's call, not this control's.** Pass a [check]
 * returning a [SuiteVerdict] and it is asked about the instant currently on the table — at the chip
 * row, at the calendar, and at the dial — so the same tap can be a plan in one app, a late entry
 * worth remarking on in another, and a refusal in a third. See [SuiteVerdict] for why "allowed, but
 * say so" is the answer that matters and the one that bounds could never express.
 */

/** An app's opinion of a picked instant. */
typealias SuiteWhenCheck = (Long) -> SuiteVerdict

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
    check: SuiteWhenCheck = { SuiteVerdict.Fine }
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
        // Whatever the app has to say about the moment currently chosen, said where the moment is —
        // including after the dialog has closed, and including for a value the chips set.
        SuiteVerdictText(check(value))
    }

    if (showPicker) {
        SuiteWhenPickerDialog(
            initial = value,
            check = check,
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
 * because Material's date and time pickers are separate components. [check] is asked at **both**
 * steps, against the whole instant each step implies — the day you are looking at combined with the
 * time already on the dial. That matters: a rule about "not in the future" is answerable on the
 * calendar for next week and only on the dial for later today, and asking once would miss one of
 * them.
 */
@Composable
fun SuiteWhenPickerDialog(
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    check: SuiteWhenCheck = { SuiteVerdict.Fine }
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

    val time = LocalTime.of(timeState.hour, timeState.minute)

    if (!showTime) {
        SuiteDatePickerDialog(
            initial = pickedDate,
            onPick = { picked -> picked?.let { pickedDate = it }; showTime = true },
            onDismiss = onDismiss,
            confirmLabel = "Next",
            // The day, carrying the time already on the dial — the instant this step would commit to
            // if the person changed nothing else.
            check = { day -> check(SuiteDates.toEpochMillis(day, time, zone)) }
        )
        return
    }

    val chosen = remember(pickedDate, timeState.hour, timeState.minute) {
        SuiteDates.toEpochMillis(pickedDate, time, zone)
    }
    val verdict = check(chosen)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What time?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimePicker(state = timeState)
                SuiteVerdictText(verdict)
            }
        },
        confirmButton = {
            TextButton(enabled = verdict.allowed, onClick = { onConfirm(chosen) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = { showTime = false }) { Text("Back") } }
    )
}
