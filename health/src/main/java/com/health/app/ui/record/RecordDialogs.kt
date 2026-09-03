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
import com.health.app.data.model.Immunization
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.ReadingType
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.Allergies
import com.health.app.logic.ConditionStatus
import com.health.app.logic.DocumentKind
import com.health.app.logic.Documents
import com.health.app.logic.Immunizations
import com.health.app.logic.VaccineSource
import com.health.app.ui.common.ChoiceRow

/**
 * The four forms behind the Record tab.
 *
 * All of them follow the rule the rest of Health's dialogs follow: **one required field, everything else
 * optional**. A record somebody has to fill in completely is a record that stays empty, and a
 * household that gave up halfway through an allergy form has recorded nothing at all — which is the
 * outcome this whole feature exists to prevent. "Penicillin" on its own is already worth having.
 *
 * The date fields take text rather than a picker, deliberately. [com.operations.suite.ui.pickers.SuiteWhenField]
 * is right for a temperature, which was taken at a moment somebody can point at on a clock; an onset
 * is a year somebody half-remembers, and a picker would force them to invent a day and a month to
 * get past it, and a vaccination card handed over at a new practice very often carries only a month.
 * See `logic/PartialDate` for the three precisions these accept.
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

/**
 * The vaccination form.
 *
 * Two fields here exist because of how this record is actually assembled, and neither is decoration:
 *
 *  - **the date takes a year on its own**, because a card handed over at a new practice very often
 *    carries only a month, and a picker demanding a day would make somebody invent one;
 *  - **the source is asked for every time**, because a dose somebody watched being given and a dose
 *    typed off a card years later are both worth recording and are not equally reliable. Health's
 *    default is "copied from a record", which is how most of a back-filled record genuinely arrives.
 */
@Composable
fun ImmunizationDialog(
    initial: Immunization? = null,
    providers: List<Provider>,
    onDismiss: () -> Unit,
    onConfirm: (ImmunizationDraft) -> Unit
) {
    var vaccine by remember { mutableStateOf(initial?.vaccine.orEmpty()) }
    var given by remember { mutableStateOf(initial?.givenDate.orEmpty()) }
    var doseNumber by remember { mutableStateOf(initial?.doseNumber?.toString().orEmpty()) }
    var source by remember { mutableStateOf(initial?.source ?: VaccineSource.TRANSCRIBED) }
    var providerId by remember { mutableStateOf(initial?.providerId) }
    var lot by remember { mutableStateOf(initial?.lotNumber.orEmpty()) }
    var site by remember { mutableStateOf(initial?.site.orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Vaccine" else "Edit vaccine") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = vaccine,
                    onValueChange = { vaccine = it },
                    label = { Text("Which vaccine?") },
                    supportingText = { Text("As it's written on the record — \"MMR\", \"DTaP\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = given,
                    onValueChange = { given = it },
                    label = { Text("When (optional)") },
                    supportingText = { Text("A year or a month is fine — 2019, 2019-03, 2019-03-14") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = doseNumber,
                    onValueChange = { doseNumber = it.filter(Char::isDigit) },
                    label = { Text("Which dose in the series? (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Where does this come from?", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = VaccineSource.entries,
                    selected = source,
                    onSelect = { source = it },
                    label = { it.label }
                )

                if (providers.isNotEmpty()) {
                    Text("Who gave it (optional)", style = MaterialTheme.typography.labelMedium)
                    ChoiceRow(
                        options = listOf<Provider?>(null) + providers,
                        selected = providers.firstOrNull { it.id == providerId },
                        onSelect = { providerId = it?.id },
                        label = { it?.name ?: "Not recorded" }
                    )
                }

                OutlinedTextField(
                    value = lot,
                    onValueChange = { lot = it },
                    label = { Text("Lot number (optional)") },
                    supportingText = { Text("Only ever wanted if there's a recall") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it },
                    label = { Text("Site (optional)") },
                    supportingText = { Text("\"Left arm\", \"left thigh\"") },
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
                    Immunizations.DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = vaccine.isNotBlank(),
                onClick = {
                    onConfirm(
                        ImmunizationDraft(
                            vaccine = vaccine.trim(),
                            givenDate = given.trim(),
                            doseNumber = doseNumber.trim(),
                            source = source,
                            providerId = providerId,
                            lotNumber = lot.trim(),
                            site = site.trim(),
                            note = note.trim()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * What to say about a file that has already been copied into the store.
 *
 * The attachment happens *before* this dialog opens — a picker's URI permission can lapse the moment
 * it closes, so the bytes are secured first and the questions asked over the top of something that is
 * already safe. Cancelling deletes the copied file rather than leaving it behind.
 *
 * The one question worth its own control is **who it is about**. A lab result belongs to a person; a
 * statement or a registration pack belongs to the house, and filing that under whoever happened to be
 * selected is how a household's paperwork ends up inside a child's medical record.
 */
@Composable
fun DocumentDialog(
    pending: PendingDocument,
    people: List<Profile>,
    defaultProfileId: String?,
    onDismiss: () -> Unit,
    onConfirm: (DocumentDraft) -> Unit
) {
    var title by remember { mutableStateOf(pending.suggestedTitle.orEmpty()) }
    var kind by remember { mutableStateOf(DocumentKind.OTHER) }
    var date by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf(defaultProfileId) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File this document") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    Documents.formatSize(pending.stored.sizeBytes)
                        ?.let { "Attached · $it" } ?: "Attached",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    supportingText = { Text("What you'd search for it by") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("What is it?", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = DocumentKind.entries,
                    selected = kind,
                    onSelect = { kind = it },
                    label = { it.label }
                )

                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date on the document (optional)") },
                    supportingText = { Text("A year or a month is fine — 2026, 2026-03, 2026-03-14") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Who is it about?", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = listOf<Profile?>(null) + people,
                    selected = people.firstOrNull { it.id == profileId },
                    onSelect = { profileId = it?.id },
                    label = { it?.name ?: "The household" }
                )

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Health keeps the file exactly as it arrived and does not read it. Nothing in it " +
                        "is searched, extracted or interpreted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        DocumentDraft(
                            title = title.trim(),
                            kind = kind,
                            documentDate = date.trim(),
                            profileId = profileId,
                            note = note.trim()
                        )
                    )
                }
            ) { Text("File it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Discard") } }
    )
}
