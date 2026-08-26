package com.health.app.ui.people

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.Profile
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.Age
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.ui.common.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PeopleViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeAllProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun setUnit(unit: TempUnit) = repo.setTemperatureUnit(unit)

    fun addProfile(name: String, relationship: String?, birthDate: String?, baselineC: Double?, notes: String?) =
        viewModelScope.launch {
            val color = HealthRepository.PROFILE_COLORS[profiles.value.size % HealthRepository.PROFILE_COLORS.size]
            repo.addProfile(name, relationship, birthDate, color, baselineC, notes)
        }

    fun updateProfile(profile: Profile) = viewModelScope.launch { repo.updateProfile(profile) }

    fun deleteProfile(profile: Profile) = viewModelScope.launch { repo.deleteProfile(profile.id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PeopleViewModel(repo) as T
    }
}

/**
 * The household. Adding someone takes a name; everything else is optional — except that a birth date
 * is what lets the fever rules know they're looking at a six-week-old rather than an adult, so the
 * form says so rather than leaving it as an unexplained field.
 */
@Composable
fun PeopleScreen(vm: PeopleViewModel, showAddInitially: Boolean = false, onAddHandled: () -> Unit = {}) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }

    // The other screens' "Add person" chip routes here with the form already open.
    LaunchedEffect(showAddInitially) {
        if (showAddInitially) {
            showAdd = true
            onAddHandled()
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add person") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (profiles.isEmpty()) {
                item(key = "empty") {
                    SectionCard(title = "Nobody here yet") {
                        Text(
                            "Health keeps a separate record for each person in the household — their " +
                                "own readings, their own medicines, their own illnesses.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    PersonCard(
                        profile = profile,
                        unit = unit,
                        isSelected = profile.id == selected?.id,
                        onSelect = { vm.select(profile) },
                        onEdit = { editTarget = profile },
                        onDelete = { deleteTarget = profile }
                    )
                }
            }

            item(key = "directory") {
                SectionCard(title = "Where these names come from") {
                    Text(
                        "The people here are the household directory's. Names, relationships and " +
                            "birth dates stay in step with the People app and LifeOps — edit one " +
                            "and the others follow.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Anyone marked a household member in People turns up here automatically, " +
                            "with their birth date — which is what the fever rules need. Nobody " +
                            "else does: a medical profile for everyone in the house would be " +
                            "noise, so it stays something you ask for.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "What stays in Health: the notes below, their usual temperature, and " +
                            "everything recorded about their health. Adding someone here marks " +
                            "them a household member; removing them un-marks them and stops Health " +
                            "tracking them — the directory keeps their record either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "settings") {
                SectionCard(title = "Display") {
                    Text("Show temperatures in", style = MaterialTheme.typography.bodySmall)
                    ChoiceRow(
                        options = TempUnit.entries,
                        selected = unit,
                        onSelect = { vm.setUnit(it) },
                        label = { if (it == TempUnit.CELSIUS) "Celsius (°C)" else "Fahrenheit (°F)" }
                    )
                    Text(
                        "Readings are always stored in Celsius and converted for display, so changing " +
                            "this never rewrites anything already recorded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "disclaimer") { DisclaimerText() }
        }
    }

    if (showAdd) {
        ProfileDialog(
            existing = null,
            unit = unit,
            onDismiss = { showAdd = false },
            onConfirm = { name, relationship, birthDate, baseline, notes ->
                vm.addProfile(name, relationship, birthDate, baseline, notes)
                showAdd = false
            }
        )
    }
    editTarget?.let { target ->
        ProfileDialog(
            existing = target,
            unit = unit,
            onDismiss = { editTarget = null },
            onConfirm = { name, relationship, birthDate, baseline, notes ->
                vm.updateProfile(
                    target.copy(
                        name = name,
                        relationship = relationship,
                        birthDate = birthDate,
                        baselineTempC = baseline,
                        notes = notes
                    )
                )
                editTarget = null
            }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove ${target.name}?") },
            text = {
                Text(
                    "This deletes their readings, symptoms, medicines, doses, illnesses and care " +
                        "notes as well. It can't be undone from inside Health — only from a backup.\n\n" +
                        "${target.name} stays in the household directory: this un-marks them as a " +
                        "household member, so Health stops tracking them and won't add them back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(target)
                    deleteTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PersonCard(
    profile: Profile,
    unit: TempUnit,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileDot(profile, size = 40, selected = isSelected)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    val details = listOfNotNull(
                        profile.relationship,
                        profile.ageLabelAt(System.currentTimeMillis()),
                        profile.baselineTempC?.let { "usually ${Temperature.format(it, unit)}" }
                    )
                    if (details.isNotEmpty()) {
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!isSelected) {
                    TextButton(onClick = onSelect) { Text("View") }
                }
            }
            profile.notes?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (profile.birthDate == null) {
                Text(
                    "No birth date — fever advice will use the adult thresholds for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    existing: Profile?,
    unit: TempUnit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, relationship: String?, birthDate: String?, baselineC: Double?, notes: String?) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var relationship by remember { mutableStateOf(existing?.relationship.orEmpty()) }
    var birthDate by remember { mutableStateOf(existing?.birthDate.orEmpty()) }
    var baseline by remember {
        mutableStateOf(existing?.baselineTempC?.let { Temperature.formatBare(it, unit) } ?: "")
    }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }

    val birthDateValid = birthDate.isBlank() || Age.parse(birthDate) != null
    val baselineC = Temperature.parseToCelsius(baseline, unit)
    val baselineValid = baseline.isBlank() || baselineC != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a person" else "Edit ${existing.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = relationship,
                    onValueChange = { relationship = it },
                    label = { Text("Who they are (optional)") },
                    placeholder = { Text("Me, Daughter, Mum…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = birthDate,
                    onValueChange = { birthDate = it },
                    label = { Text("Birth date (YYYY-MM-DD)") },
                    isError = !birthDateValid,
                    supportingText = {
                        Text(
                            if (!birthDateValid) "Use YYYY-MM-DD."
                            else "Optional, but it's what makes the fever advice age-aware — the " +
                                "thresholds for a baby are not the ones for an adult."
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DecimalField(
                    value = baseline,
                    onValueChange = { baseline = it },
                    label = "Their usual temperature (optional, ${unit.symbol})",
                    isError = !baselineValid,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Allergies, conditions, the doctor's number…") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && birthDateValid && baselineValid,
                onClick = {
                    onConfirm(
                        name.trim(),
                        relationship.ifBlank { null },
                        birthDate.ifBlank { null },
                        baselineC,
                        notes.ifBlank { null }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
