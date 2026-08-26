package com.health.app.ui.record

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Document
import com.health.app.data.model.Immunization
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.logic.AllergySeverity
import com.health.app.logic.Allergies
import com.health.app.logic.ConditionStatus
import com.health.app.logic.Immunizations
import com.health.app.logic.VaccineSeries
import com.health.app.ui.common.NoProfiles
import com.health.app.ui.common.ProfileBar
import com.health.app.ui.common.SectionCard

/**
 * The Record tab: **what is true about this person between illnesses**.
 *
 * Every other tab in Health records something that *happened* — a temperature was taken, a dose was
 * given, an illness ran from Tuesday to Sunday. This one holds what simply *is*: what she must not
 * be given, what she already has, what she has been vaccinated against, and the paperwork behind all
 * of it. They are the facts a babysitter, a school form and a triage nurse all ask for first, and
 * until now the first two lived in a free-text note that nothing could read back while the rest lived
 * in a drawer.
 *
 * The screen's one firm rule runs through every tab: **an empty list is never rendered as an
 * all-clear**. "Nothing recorded" and "no allergies" are different sentences; so are "three doses
 * recorded" and "up to date". Only one of each pair is something this app is in a position to say.
 */
@Composable
fun RecordScreen(vm: RecordViewModel, onAddProfile: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val vaccineSeries by vm.vaccineSeries.collectAsStateWithLifecycle()
    val immunizationsById by vm.immunizationsById.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val householdDocuments by vm.householdDocuments.collectAsStateWithLifecycle()
    val pendingDocument by vm.pendingDocument.collectAsStateWithLifecycle()
    val documentMessage by vm.documentMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Any type: a household is handed PDFs, photographs, scans and the occasional Word file, and a
    // picker that refused one of them would just send somebody to a different app to convert it.
    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.attach(it) } }

    var tab by remember { mutableIntStateOf(0) }
    var addingAllergy by remember { mutableStateOf(false) }
    var addingCondition by remember { mutableStateOf(false) }
    var editingAllergy by remember { mutableStateOf<Allergy?>(null) }
    var editingCondition by remember { mutableStateOf<Condition?>(null) }
    var addingVaccine by remember { mutableStateOf(false) }
    var editingVaccine by remember { mutableStateOf<Immunization?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onAddProfile)
        return
    }
    val person = selected

    Scaffold(
        floatingActionButton = {
            if (person != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        when (tab) {
                            0 -> addingAllergy = true
                            1 -> addingCondition = true
                            2 -> addingVaccine = true
                            else -> pickDocument.launch(arrayOf("*/*"))
                        }
                    },
                    icon = {
                        Icon(
                            if (tab == 3) Icons.Default.AttachFile else Icons.Default.Add,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            when (tab) {
                                0 -> "Allergy"
                                1 -> "Condition"
                                2 -> "Vaccine"
                                else -> "Attach"
                            }
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ProfileBar(profiles, selected?.id, vm::select, onAddProfile)
            HorizontalDivider()

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Allergies") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Conditions") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Vaccines") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Documents") })
            }

            documentMessage?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissDocumentMessage) { Text("Dismiss") }
                    }
                }
            }

            when (tab) {
                0 -> AllergyList(
                    allergies = record.allergies,
                    person = person,
                    onEdit = { editingAllergy = it },
                    onDelete = { vm.deleteAllergy(it.id) }
                )
                1 -> ConditionList(
                    conditions = record.conditions,
                    providers = providers,
                    person = person,
                    onEdit = { editingCondition = it },
                    onDelete = { vm.deleteCondition(it.id) }
                )
                2 -> VaccineList(
                    series = vaccineSeries,
                    person = person,
                    onEdit = { doseId -> immunizationsById[doseId]?.let { editingVaccine = it } },
                    onDelete = { doseId -> vm.deleteImmunization(doseId) }
                )
                else -> DocumentList(
                    documents = documents,
                    householdDocuments = householdDocuments,
                    person = person,
                    onOpen = { vm.openDocument(context, it) },
                    onDelete = { vm.deleteDocument(it.id) }
                )
            }
        }
    }

    if (addingAllergy && person != null) {
        AllergyDialog(
            onDismiss = { addingAllergy = false },
            onConfirm = { draft ->
                vm.addAllergy(person.id, draft)
                addingAllergy = false
            }
        )
    }
    editingAllergy?.let { allergy ->
        AllergyDialog(
            initial = allergy,
            onDismiss = { editingAllergy = null },
            onConfirm = { draft ->
                vm.updateAllergy(
                    allergy.copy(
                        substance = draft.substance,
                        kind = draft.kind,
                        severity = draft.severity,
                        reaction = draft.reaction.ifBlank { null },
                        noticedDate = draft.noticedDate.ifBlank { null },
                        note = draft.note.ifBlank { null }
                    )
                )
                editingAllergy = null
            }
        )
    }

    if (addingCondition && person != null) {
        ConditionDialog(
            providers = providers,
            onDismiss = { addingCondition = false },
            onConfirm = { draft ->
                vm.addCondition(person.id, draft)
                addingCondition = false
            }
        )
    }
    editingCondition?.let { condition ->
        ConditionDialog(
            initial = condition,
            providers = providers,
            onDismiss = { editingCondition = null },
            onConfirm = { draft ->
                vm.updateCondition(
                    condition.copy(
                        name = draft.name,
                        status = draft.status,
                        onsetDate = draft.onsetDate.ifBlank { null },
                        resolvedDate = draft.resolvedDate.ifBlank { null },
                        providerId = draft.providerId,
                        monitorReadingType = draft.monitorReadingType,
                        note = draft.note.ifBlank { null }
                    )
                )
                editingCondition = null
            }
        )
    }

    if (addingVaccine && person != null) {
        ImmunizationDialog(
            providers = providers,
            onDismiss = { addingVaccine = false },
            onConfirm = { draft ->
                vm.addImmunization(person.id, draft)
                addingVaccine = false
            }
        )
    }
    editingVaccine?.let { immunization ->
        ImmunizationDialog(
            initial = immunization,
            providers = providers,
            onDismiss = { editingVaccine = null },
            onConfirm = { draft ->
                vm.updateImmunization(
                    immunization.copy(
                        vaccine = draft.vaccine,
                        givenDate = draft.givenDate.ifBlank { null },
                        doseNumber = draft.dose,
                        source = draft.source,
                        providerId = draft.providerId,
                        lotNumber = draft.lotNumber.ifBlank { null },
                        site = draft.site.ifBlank { null },
                        note = draft.note.ifBlank { null }
                    )
                )
                editingVaccine = null
            }
        )
    }

    pendingDocument?.let { pending ->
        DocumentDialog(
            pending = pending,
            people = profiles,
            defaultProfileId = person?.id,
            onDismiss = vm::cancelPendingDocument,
            onConfirm = { draft -> vm.filePendingDocument(draft) }
        )
    }
}

/**
 * The household's paperwork, in two lists that are deliberately not one.
 *
 * A lab result is about a person and a statement is about the house, and folding them together would
 * put the family's insurance paperwork inside a child's medical record. So the selected person's
 * documents come first and the household's follow, under their own heading, visible whoever is
 * selected — because that is what "the household's" means.
 *
 * Nothing here reads a document. Opening one hands it to whatever app the device has; Health copies
 * it out to `cacheDir/exports` for exactly that moment and never exposes the store itself.
 */
@Composable
private fun DocumentList(
    documents: List<Document>,
    householdDocuments: List<Document>,
    person: Profile?,
    onOpen: (Document) -> Unit,
    onDelete: (Document) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (documents.isEmpty() && householdDocuments.isEmpty()) {
            item {
                SectionCard("Nothing filed") {
                    Text(
                        "After-visit summaries, lab results, referral letters, school forms — the " +
                            "paper that arrives and then can't be found. Attach a PDF or a photo of " +
                            "one and it is carried by the backup with everything else.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (documents.isNotEmpty()) {
            item {
                Text(
                    person?.let { "${it.name}'s documents" } ?: "Documents",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(documents, key = { it.id }) { document ->
                DocumentCard(document, onOpen = { onOpen(document) }, onDelete = { onDelete(document) })
            }
        }

        if (householdDocuments.isNotEmpty()) {
            item {
                Text(
                    "The household's",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(householdDocuments, key = { it.id }) { document ->
                DocumentCard(document, onOpen = { onOpen(document) }, onDelete = { onDelete(document) })
            }
        }

        item {
            Text(
                "Health stores these files and does not read them — nothing in a document is " +
                    "searched, extracted or interpreted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DocumentCard(document: Document, onOpen: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                document.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                document.descriptor,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            document.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * The vaccination record, grouped into series.
 *
 * There is no "due", no "overdue" and no green tick anywhere on this list, and that absence is the
 * feature — see `logic/Immunizations`. Health reports how many doses are **recorded** and when the
 * latest was; it ships no schedule and cannot have an opinion about what is missing.
 */
@Composable
private fun VaccineList(
    series: List<VaccineSeries>,
    person: Profile?,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (series.isEmpty()) {
            item {
                SectionCard("Nothing recorded") {
                    Text(
                        "The card in the drawer, typed up — the list a school, a camp or a new " +
                            "practice asks for. Add what you have; a year on its own is worth " +
                            "recording, and so is a dose you only remember.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            item {
                Text(
                    "${Immunizations.totalRecorded(series)} doses recorded across " +
                        "${series.size} ${if (series.size == 1) "vaccine" else "vaccines"}" +
                        (person?.let { " for ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            items(series, key = { it.key }) { entry ->
                SectionCard(entry.name) {
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entry.doses.forEach { dose ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(dose.descriptor, style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { onEdit(dose.id) }) { Text("Edit") }
                                TextButton(onClick = { onDelete(dose.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                Immunizations.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AllergyList(
    allergies: List<Allergy>,
    person: Profile?,
    onEdit: (Allergy) -> Unit,
    onDelete: (Allergy) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (allergies.isEmpty()) {
            item {
                SectionCard("Nothing recorded") {
                    // The distinction the whole feature turns on. An app that rendered this as a
                    // green tick would be making a medical claim on no evidence at all.
                    Text(
                        "Health has no allergies recorded for ${person?.name ?: "this person"}. " +
                            "That is not the same as knowing there aren't any — it means nobody has " +
                            "written any down here yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(allergies, key = { it.id }) { allergy ->
                AllergyCard(allergy, onEdit = { onEdit(allergy) }, onDelete = { onDelete(allergy) })
            }
        }

        item { LegacyNoteCard(person) }

        item {
            Text(
                Allergies.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AllergyCard(allergy: Allergy, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    allergy.substance,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                SeverityBadge(allergy.severity)
            }
            allergy.descriptor?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            allergy.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** Red is reserved in Health. It is spent here on the two reactions that earn it and nowhere else. */
@Composable
private fun SeverityBadge(severity: AllergySeverity) {
    val color: Color = when (severity) {
        AllergySeverity.ANAPHYLAXIS, AllergySeverity.SEVERE -> MaterialTheme.colorScheme.error
        AllergySeverity.MODERATE -> MaterialTheme.colorScheme.tertiary
        AllergySeverity.MILD, AllergySeverity.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            severity.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * The old free-text note, shown here on purpose.
 *
 * The v7 migration deliberately parsed nothing out of it — reading "penicillin (hives), asthma, Dr
 * Okafor 555-0101" into rows means guessing at exactly the data where a wrong guess is worst. So the
 * note still says whatever it always said, and this card is how somebody finds out there is
 * something in it worth re-entering as a row that can actually be checked.
 */
@Composable
private fun LegacyNoteCard(person: Profile?) {
    val note = person?.notes?.trim().orEmpty()
    if (note.isBlank()) return
    SectionCard("Also on this person's profile") {
        Text(note, style = MaterialTheme.typography.bodyMedium)
        Text(
            "This is the free-text note from ${person?.name ?: "this person"}'s profile. Health has " +
                "not read anything out of it — anything in there that should be checked against a " +
                "medicine needs adding as an allergy above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConditionList(
    conditions: List<Condition>,
    providers: List<Provider>,
    person: Profile?,
    onEdit: (Condition) -> Unit,
    onDelete: (Condition) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (conditions.isEmpty()) {
            item {
                SectionCard("Nothing recorded") {
                    Text(
                        "Long-running things live here — asthma, eczema, coeliac, anything somebody " +
                            "is keeping an eye on. They are deliberately kept apart from illnesses: " +
                            "an illness has an end, and these don't.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(conditions, key = { it.id }) { condition ->
                ConditionCard(
                    condition = condition,
                    provider = providers.firstOrNull { it.id == condition.providerId },
                    onEdit = { onEdit(condition) },
                    onDelete = { onDelete(condition) }
                )
            }
        }
    }
}

@Composable
private fun ConditionCard(
    condition: Condition,
    provider: Provider?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    condition.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StatusBadge(condition.status)
            }
            condition.sinceLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            provider?.let {
                Text("Managed by ${it.name}", style = MaterialTheme.typography.bodySmall)
            }
            condition.monitorReadingType?.let {
                Text("Watched with: ${it.label}", style = MaterialTheme.typography.bodySmall)
            }
            condition.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ConditionStatus) {
    val color = when (status) {
        ConditionStatus.ACTIVE -> MaterialTheme.colorScheme.tertiary
        ConditionStatus.REMISSION -> MaterialTheme.colorScheme.primary
        ConditionStatus.RESOLVED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            status.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
