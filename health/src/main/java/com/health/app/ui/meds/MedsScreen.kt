package com.health.app.ui.meds

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.Dose
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.Profile
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DoseStatus
import com.health.app.ui.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MedsViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val statuses: StateFlow<List<MedicationStatus>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.observeMedicationStatuses(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val doses: StateFlow<List<Dose>> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(emptyList()) else repo.observeDoses(profile.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun addMedication(
        name: String,
        strength: String?,
        doseAmount: Double?,
        doseUnit: String,
        minIntervalHours: Double?,
        maxDosesPer24h: Int?,
        maxAmountPer24h: Double?
    ) = viewModelScope.launch {
        val profile = selected.value ?: return@launch
        repo.addMedication(
            profileId = profile.id,
            name = name,
            strength = strength,
            form = null,
            doseAmount = doseAmount,
            doseUnit = doseUnit,
            minIntervalHours = minIntervalHours,
            maxDosesPer24h = maxDosesPer24h,
            maxAmountPer24h = maxAmountPer24h
        )
    }

    fun give(medication: Medication) = viewModelScope.launch { repo.logDoseOf(medication) }

    fun logDose(medication: Medication?, name: String, amount: Double, unit: String, note: String?) =
        viewModelScope.launch {
            val profile = selected.value ?: return@launch
            repo.logDose(profile.id, medication?.id, name, amount, unit, note = note)
        }

    fun setActive(medication: Medication, active: Boolean) = viewModelScope.launch {
        repo.updateMedication(medication.copy(active = active))
    }

    fun deleteMedication(medication: Medication) = viewModelScope.launch { repo.deleteMedication(medication.id) }

    fun deleteDose(dose: Dose) = viewModelScope.launch { repo.deleteDose(dose.id) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedsViewModel(repo) as T
    }
}

/**
 * Medicines and the doses actually given. Each medicine shows its own dose window — due now, wait
 * this long, or the daily allowance is spent — computed by `logic/DoseSchedule` from the label's own
 * rules, which is why those rules are worth typing in once.
 */
@Composable
fun MedsScreen(vm: MedsViewModel, onAddProfile: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val doses by vm.doses.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var showDose by remember { mutableStateOf(false) }

    if (profiles.isEmpty()) {
        NoProfiles(onAddProfile)
        return
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Medicine") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ProfileBar(profiles, selected?.id, vm::select, onAddProfile)
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (statuses.isEmpty()) {
                    item(key = "empty") {
                        SectionCard(title = "No medicines yet") {
                            Text(
                                "Add one with the spacing and daily limit printed on its label. Health " +
                                    "then answers the only question that matters at 3am — whether the " +
                                    "next dose is due — instead of leaving you to do the arithmetic.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    items(statuses, key = { it.medication.id }) { status ->
                        MedicationCard(
                            status = status,
                            onGive = { vm.give(status.medication) },
                            onToggleActive = { vm.setActive(status.medication, !status.medication.active) },
                            onDelete = { vm.deleteMedication(status.medication) }
                        )
                    }
                }

                item(key = "log-dose") {
                    OutlinedButton(onClick = { showDose = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Record a dose given")
                    }
                }

                item(key = "history-header") {
                    Text("Doses given", style = MaterialTheme.typography.titleSmall)
                }
                if (doses.isEmpty()) {
                    item(key = "no-doses") {
                        Text("Nothing given yet.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    items(doses, key = { it.id }) { dose ->
                        RecordRow(
                            headline = dose.medicationName,
                            support = listOfNotNull(
                                "${trimAmount(dose.amount)} ${dose.unit}".trim(),
                                formatStamp(dose.takenAt),
                                dose.note
                            ).joinToString(" · "),
                            onDelete = { vm.deleteDose(dose) }
                        )
                    }
                }

                item(key = "disclaimer") { DisclaimerText() }
            }
        }
    }

    if (showAdd) {
        AddMedicationDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, strength, amount, unit, interval, maxDoses, maxAmount ->
                vm.addMedication(name, strength, amount, unit, interval, maxDoses, maxAmount)
                showAdd = false
            }
        )
    }
    if (showDose) {
        LogDoseDialog(
            medications = statuses.map { it.medication }.filter { it.active },
            onDismiss = { showDose = false },
            onConfirm = { medication, name, amount, unit, note ->
                vm.logDose(medication, name, amount, unit, note)
                showDose = false
            }
        )
    }
}

@Composable
private fun MedicationCard(
    status: MedicationStatus,
    onGive: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit
) {
    val medication = status.medication
    val window = status.window
    val now = System.currentTimeMillis()
    val statusColor = when (window.status) {
        DoseStatus.READY -> MaterialTheme.colorScheme.primary
        DoseStatus.WAIT -> MaterialTheme.colorScheme.onSurfaceVariant
        DoseStatus.LIMIT_REACHED -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(medication.name, style = MaterialTheme.typography.titleMedium)
                    listOfNotNull(
                        medication.strength,
                        medication.doseAmount?.let { "${trimAmount(it)} ${medication.doseUnit}" },
                        medication.minIntervalHours?.let { "every ${trimAmount(it)}h" },
                        medication.maxDosesPer24h?.let { "max $it/day" },
                        medication.maxAmountPer24h?.let { "max ${trimAmount(it)} ${medication.doseUnit}/day" }
                    ).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (window.isReady && medication.active) {
                    Button(onClick = onGive) { Text("Give") }
                } else {
                    Text(
                        when (window.status) {
                            DoseStatus.READY -> "Paused"
                            DoseStatus.WAIT -> DoseSchedule.formatDuration(window.waitMillis(now))
                            DoseStatus.LIMIT_REACHED -> "Limit"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                }
            }

            Text(window.reason, style = MaterialTheme.typography.bodySmall, color = statusColor)
            window.lastDoseAtMillis?.let {
                Text(
                    "Last given ${formatStamp(it)} (${DoseSchedule.formatAgo(now - it)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleActive) {
                    Text(if (medication.active) "Pause" else "Resume")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/**
 * Adding a medicine is mostly copying the label. Every limit is optional, because a rule Health
 * invented is a rule that will eventually be wrong in a way nobody typed in.
 */
@Composable
private fun AddMedicationDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        strength: String?,
        doseAmount: Double?,
        doseUnit: String,
        minIntervalHours: Double?,
        maxDosesPer24h: Int?,
        maxAmountPer24h: Double?
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mL") }
    var interval by remember { mutableStateOf("") }
    var maxDoses by remember { mutableStateOf("") }
    var maxAmount by remember { mutableStateOf("") }

    fun decimal(text: String) = text.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a medicine") },
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
                    value = strength,
                    onValueChange = { strength = it },
                    label = { Text("Strength, as printed (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(amount, { amount = it }, "Usual dose", Modifier.weight(1f))
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                DecimalField(
                    value = interval,
                    onValueChange = { interval = it },
                    label = "Minimum hours between doses",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(maxDoses, { maxDoses = it }, "Max doses / 24h", Modifier.weight(1f))
                    DecimalField(maxAmount, { maxAmount = it }, "Max amount / 24h", Modifier.weight(1f))
                }
                Text(
                    "Leave a limit blank and Health won't enforce it — it tracks what the label says, " +
                        "not what it guesses.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.trim(),
                        strength.ifBlank { null },
                        decimal(amount),
                        unit.trim(),
                        decimal(interval),
                        decimal(maxDoses)?.toInt(),
                        decimal(maxAmount)
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
