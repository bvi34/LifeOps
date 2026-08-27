package com.health.app.ui.information

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Episode
import com.health.app.data.model.Medication
import com.health.app.data.model.Profile
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.logic.TimelineKind
import com.health.app.ui.common.*

/**
 * The two forms that write into an illness after the fact: moving its dates, and adding something
 * that happened while it was running.
 *
 * Both exist because a history you can only write at the moment things happen is a history that
 * mostly doesn't get written — see the backfilling section of docs/HEALTH.md.
 */

/**
 * When an illness actually ran.
 *
 * The piece that makes reconstructing a past illness possible: "we had the flu the first week of
 * March" is an episode with **both** ends in the past, and without a way to say so there is nowhere
 * to hang the records of it.
 *
 * Moving the dates re-files the records — anything unattached inside the new span is adopted,
 * anything of this episode's now outside it is released — so the span always means what it says. The
 * warning below is worth showing because that is a bigger consequence than "edit dates" suggests.
 */
@Composable
internal fun EpisodeDatesDialog(
    episode: Episode,
    onDismiss: () -> Unit,
    onConfirm: (startedAt: Long, endedAt: Long?) -> Unit
) {
    var startedAt by remember { mutableLongStateOf(episode.startedAt) }
    var stillGoing by remember { mutableStateOf(episode.isOpen) }
    var endedAt by remember { mutableLongStateOf(episode.endedAt ?: System.currentTimeMillis()) }

    val backwards = !stillGoing && endedAt < startedAt

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(episode.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WhenField(value = startedAt, onValueChange = { startedAt = it }, label = "Started")

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Still going", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = stillGoing, onCheckedChange = { stillGoing = it })
                }

                if (!stillGoing) {
                    WhenField(value = endedAt, onValueChange = { endedAt = it }, label = "Over")
                }

                if (backwards) {
                    Text(
                        "It can't have ended before it started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    "Records inside these dates are filed under this illness. Widening the span " +
                        "adopts anything that wasn't filed anywhere; narrowing it releases what " +
                        "falls outside. Nothing filed under another illness is touched, and no " +
                        "record is ever deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !backwards,
                onClick = { onConfirm(startedAt, endedAt.takeUnless { stillGoing }) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Pick what to add, then add it — the four record dialogs, reached from the history.
 *
 * The same dialogs the Today tab uses, deliberately: one form per kind of record, and the "when"
 * field they all carry is what makes them work as a backfill tool without needing a second set of
 * forms that could drift out of step with the first.
 */
@Composable
internal fun BackfillDialogs(
    profile: Profile?,
    medications: List<Medication>,
    unit: TempUnit,
    onRecord: (BackfillRecord) -> Unit,
    onDismiss: () -> Unit
) {
    var choice by remember { mutableStateOf<TimelineKind?>(null) }

    when (choice) {
        null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("What happened?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Anything you remember. Health files it under the illness that was going " +
                            "on when it happened, so the date you give it is what matters — not " +
                            "today's.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    listOf(
                        TimelineKind.READING to "A temperature",
                        TimelineKind.DOSE to "A dose given",
                        TimelineKind.SYMPTOM_STARTED to "A symptom",
                        TimelineKind.CARE to "Something done — fluids, a call, a test"
                    ).forEach { (kind, label) ->
                        TextButton(onClick = { choice = kind }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        TimelineKind.READING -> LogTemperatureDialog(
            unit = unit,
            ageMonths = profile?.ageMonthsAt(System.currentTimeMillis()),
            onDismiss = onDismiss,
            onConfirm = { celsius, site, note, at ->
                onRecord(BackfillRecord.Temperature(celsius, site, note, at))
                onDismiss()
            }
        )

        TimelineKind.DOSE -> LogDoseDialog(
            medications = medications,
            onDismiss = onDismiss,
            onConfirm = { medication, name, amount, doseUnit, note, at ->
                onRecord(BackfillRecord.Dose(medication, name, amount, doseUnit, note, at))
                onDismiss()
            }
        )

        TimelineKind.SYMPTOM_STARTED -> AddSymptomDialog(
            onDismiss = onDismiss,
            onConfirm = { name, severity, note, startedAt ->
                onRecord(BackfillRecord.Symptom(name, severity, note, startedAt))
                onDismiss()
            }
        )

        else -> CareNoteDialog(
            onDismiss = onDismiss,
            onConfirm = { kind, text, at ->
                onRecord(BackfillRecord.Care(kind, text, at))
                onDismiss()
            }
        )
    }
}
