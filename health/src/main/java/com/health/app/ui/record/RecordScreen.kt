package com.health.app.ui.record

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.logic.AllergySeverity
import com.health.app.logic.Allergies
import com.health.app.logic.ConditionStatus
import com.health.app.ui.common.NoProfiles
import com.health.app.ui.common.ProfileBar
import com.health.app.ui.common.SectionCard

/**
 * The Record tab: **what is true about this person between illnesses**.
 *
 * Every other tab in Health records something that *happened* — a temperature was taken, a dose was
 * given, an illness ran from Tuesday to Sunday. This one holds what simply *is*: what she must not
 * be given, and what she already has. They are the two facts a babysitter, a school form and a
 * triage nurse all ask for first, and until now they lived in a free-text note that nothing could
 * read back.
 *
 * The screen's one firm rule is that **an empty list is never rendered as an all-clear**. "Nothing
 * recorded" and "no allergies" are different sentences, and only one of them is something this app
 * is in a position to say.
 */
@Composable
fun RecordScreen(vm: RecordViewModel, onAddProfile: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var addingAllergy by remember { mutableStateOf(false) }
    var addingCondition by remember { mutableStateOf(false) }
    var editingAllergy by remember { mutableStateOf<Allergy?>(null) }
    var editingCondition by remember { mutableStateOf<Condition?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onAddProfile)
        return
    }
    val person = selected

    Scaffold(
        floatingActionButton = {
            if (person != null) {
                ExtendedFloatingActionButton(
                    onClick = { if (tab == 0) addingAllergy = true else addingCondition = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(if (tab == 0) "Allergy" else "Condition") }
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
            }

            when (tab) {
                0 -> AllergyList(
                    allergies = record.allergies,
                    person = person,
                    onEdit = { editingAllergy = it },
                    onDelete = { vm.deleteAllergy(it.id) }
                )
                else -> ConditionList(
                    conditions = record.conditions,
                    providers = providers,
                    person = person,
                    onEdit = { editingCondition = it },
                    onDelete = { vm.deleteCondition(it.id) }
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
