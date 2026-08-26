package com.health.app.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Provider
import com.health.app.data.model.ReadingType
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.Allergies
import com.health.app.logic.ConditionStatus
import com.health.app.ui.common.ChoiceRow

/**
 * The two forms behind the Record tab.
 *
 * Both follow the rule the rest of Health's dialogs follow: **one required field, everything else
 * optional**. A record somebody has to fill in completely is a record that stays empty, and a
 * household that gave up halfway through an allergy form has recorded nothing at all — which is the
 * outcome this whole feature exists to prevent. "Penicillin" on its own is already worth having.
 *
 * The date fields take text rather than a picker, deliberately. [com.health.app.ui.common.WhenField]
 * is right for a temperature, which was taken at a moment somebody can point at on a clock; an onset
 * is a year somebody half-remembers, and a picker would force them to invent a day and a month to
 * get past it. See `Conditions.parseOnset` for the three precisions this accepts.
 */

@Composable
fun AllergyDialog(
    initial: Allergy? = null,
    onDismiss: () -> Unit,
    onConfirm: (AllergyDraft) -> Unit
) {
    var substance by remember { mutableStateOf(initial?.substance.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: AllergyKind.DRUG) }
    var severity by remember { mutableStateOf(initial?.severity ?: AllergySeverity.UNKNOWN) }
    var reaction by remember { mutableStateOf(initial?.reaction.orEmpty()) }
    var noticed by remember { mutableStateOf(initial?.noticedDate.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Allergy" else "Edit allergy") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = substance,
                    onValueChange = { substance = it },
                    label = { Text("What are they allergic to?") },
                    supportingText = { Text("The name as you'd say it — \"penicillin\", \"peanuts\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Kind", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = AllergyKind.entries,
                    selected = kind,
                    onSelect = { kind = it },
                    label = { it.label }
                )
                if (kind != AllergyKind.DRUG) {
                    // Said plainly rather than hidden, because the alternative is a household
                    // believing a check happened that never did.
                    Text(
                        "Health only checks medicines against medicine allergies. This one is kept on " +
                            "the record for you to read, not compared against anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text("How bad was it?", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = AllergySeverity.entries,
                    selected = severity,
                    onSelect = { severity = it },
                    label = { if (it == AllergySeverity.UNKNOWN) "Not recorded" else it.label }
                )

                OutlinedTextField(
                    value = reaction,
                    onValueChange = { reaction = it },
                    label = { Text("What happened? (optional)") },
                    supportingText = { Text("\"Hives\", \"throat swelling\" — what a doctor asks first") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = noticed,
                    onValueChange = { noticed = it },
                    label = { Text("First noticed (optional)") },
                    supportingText = { Text("A year is enough — 2019, or 2019-03, or 2019-03-14") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    Allergies.DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = substance.isNotBlank(),
                onClick = {
                    onConfirm(
                        AllergyDraft(
                            substance = substance.trim(),
                            kind = kind,
                            severity = severity,
                            reaction = reaction.trim(),
                            noticedDate = noticed.trim(),
                            note = note.trim()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConditionDialog(
    initial: Condition? = null,
    providers: List<Provider>,
    onDismiss: () -> Unit,
    onConfirm: (ConditionDraft) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var status by remember { mutableStateOf(initial?.status ?: ConditionStatus.ACTIVE) }
    var onset by remember { mutableStateOf(initial?.onsetDate.orEmpty()) }
    var resolved by remember { mutableStateOf(initial?.resolvedDate.orEmpty()) }
    var providerId by remember { mutableStateOf(initial?.providerId) }
    var monitor by remember { mutableStateOf(initial?.monitorReadingType) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Condition" else "Edit condition") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it?") },
                    supportingText = { Text("\"Asthma\", \"eczema\", \"coeliac disease\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Where it stands", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = ConditionStatus.entries,
                    selected = status,
                    onSelect = { status = it },
                    label = { it.label }
                )

                OutlinedTextField(
                    value = onset,
                    onValueChange = { onset = it },
                    label = { Text("Since (optional)") },
                    supportingText = { Text("A year is enough — 2019, or 2019-03, or 2019-03-14") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (status == ConditionStatus.RESOLVED) {
                    OutlinedTextField(
                        value = resolved,
                        onValueChange = { resolved = it },
                        label = { Text("Until (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (providers.isNotEmpty()) {
                    Text("Who manages it (optional)", style = MaterialTheme.typography.labelMedium)
                    ChoiceRow(
                        options = listOf<Provider?>(null) + providers,
                        selected = providers.firstOrNull { it.id == providerId },
                        onSelect = { providerId = it?.id },
                        label = { it?.name ?: "Nobody recorded" }
                    )
                }

                // The chronic-care hook: the one measurement that matters for this condition. Left
                // empty for most of them, because Health has no table of which vital belongs to which
                // diagnosis and is not about to invent one.
                Text("The reading that matters (optional)", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = listOf<ReadingType?>(null) + ReadingType.entries,
                    selected = monitor,
                    onSelect = { monitor = it },
                    label = { it?.label ?: "None" }
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        ConditionDraft(
                            name = name.trim(),
                            status = status,
                            onsetDate = onset.trim(),
                            resolvedDate = if (status == ConditionStatus.RESOLVED) resolved.trim() else "",
                            providerId = providerId,
                            monitorReadingType = monitor,
                            note = note.trim()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
