package com.health.app.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.health.app.data.model.CareKind
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.Profile
import com.health.app.data.model.ProfileSnapshot
import com.health.app.data.repository.HealthRepository
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DoseStatus
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.ui.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Today screen's state. Everything is derived from the selected profile, so switching person
 * switches the whole screen without any screen-level bookkeeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val repo: HealthRepository) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<TempUnit> =
        repo.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val snapshot: StateFlow<ProfileSnapshot?> = selected
        .flatMapLatest { profile -> if (profile == null) flowOf(null) else repo.observeSnapshot(profile) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val medications: StateFlow<List<Medication>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.observeMedications(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(profile: Profile) = repo.selectProfile(profile.id)

    fun logTemperature(celsius: Double, site: TempSite, note: String?, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.logTemperature(it.id, celsius, site, takenAt = at, note = note) }
    }

    fun logDose(
        medication: Medication?,
        name: String,
        amount: Double,
        unit: String,
        note: String?,
        at: Long
    ) =
        viewModelScope.launch {
            val profile = selected.value ?: return@launch
            repo.logDose(profile.id, medication?.id, name, amount, unit, takenAt = at, note = note)
        }

    fun addSymptom(name: String, severity: Int, note: String?, startedAt: Long) = viewModelScope.launch {
        selected.value?.let { repo.addSymptom(it.id, name, severity, startedAt = startedAt, note = note) }
    }

    fun resolveSymptom(symptomId: String) = viewModelScope.launch { repo.setSymptomEnded(symptomId) }

    fun addCareNote(kind: CareKind, text: String, at: Long) = viewModelScope.launch {
        selected.value?.let { repo.addCareNote(it.id, kind, text, at = at) }
    }

    fun startEpisode(title: String, startedAt: Long = System.currentTimeMillis()) = viewModelScope.launch {
        selected.value?.let { repo.startEpisode(it.id, title, startedAt = startedAt) }
    }

    fun endEpisode(episodeId: String) = viewModelScope.launch { repo.endEpisode(episodeId) }

    class Factory(private val repo: HealthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayViewModel(repo) as T
    }
}

/**
 * The cockpit: who's ill, how they are right now, what's due, and four buttons that record the
 * things you're actually holding a phone to record.
 */
@Composable
fun TodayScreen(vm: TodayViewModel, onAddProfile: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val medications by vm.medications.collectAsStateWithLifecycle()

    var showTemp by remember { mutableStateOf(false) }
    var showDose by remember { mutableStateOf(false) }
    var showSymptom by remember { mutableStateOf(false) }
    var showCareNote by remember { mutableStateOf(false) }
    var showStartEpisode by remember { mutableStateOf(false) }

    if (profiles.isEmpty()) {
        NoProfiles(onAddProfile)
        return
    }

    Column(Modifier.fillMaxSize()) {
        ProfileBar(
            profiles = profiles,
            selectedId = selected?.id,
            onSelect = vm::select,
            onAddProfile = onAddProfile
        )
        HorizontalDivider()

        val current = snapshot
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (current == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            TemperatureCard(current, unit)

            val episode = current.openEpisode
            if (episode != null) {
                SectionCard(
                    title = "Illness in progress",
                    trailing = { TextButton(onClick = { vm.endEpisode(episode.id) }) { Text("Mark over") } }
                ) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Started ${formatStamp(episode.startedAt)} · " +
                            "${DoseSchedule.formatDuration(System.currentTimeMillis() - episode.startedAt)} so far",
                        style = MaterialTheme.typography.bodySmall
                    )
                    episode.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Everything you record now is filed against this illness.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SectionCard(title = "No illness open") {
                    Text(
                        "Start one when someone comes down with something, and every reading, dose " +
                            "and note from then on is kept together — including the ones already " +
                            "taken in the last few hours.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { showStartEpisode = true }) { Text("Start an illness") }
                }
            }

            SectionCard(title = "Medicines due") {
                if (current.medications.isEmpty()) {
                    Text(
                        "No medicines set up for ${current.profile.name} yet — add one on the Meds tab " +
                            "and Health will track the spacing and the daily limit for you.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    current.medications.forEach { status ->
                        // The one-tap "Give" is by definition happening now — that is what the
                        // button means, and asking when would defeat the point of it being one tap.
                        MedicationDueRow(status) {
                            vm.logDose(
                                it, it.name, it.doseAmount ?: 0.0, it.doseUnit, null,
                                System.currentTimeMillis()
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Symptoms now") {
                if (current.activeSymptoms.isEmpty()) {
                    Text("Nothing recorded as ongoing.", style = MaterialTheme.typography.bodySmall)
                } else {
                    current.activeSymptoms.forEach { symptom ->
                        RecordRow(
                            headline = symptom.name,
                            support = "${severityLabel(symptom.severity)} · since ${formatStamp(symptom.startedAt)}",
                            trailing = "Resolved",
                            onClick = { vm.resolveSymptom(symptom.id) }
                        )
                    }
                }
            }

            SectionCard(title = "Record") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction("Temp", Icons.Default.Thermostat, Modifier.weight(1f)) { showTemp = true }
                    QuickAction("Dose", Icons.Default.MedicalServices, Modifier.weight(1f)) { showDose = true }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction("Symptom", Icons.Default.Sick, Modifier.weight(1f)) { showSymptom = true }
                    QuickAction("Care note", Icons.Default.Notes, Modifier.weight(1f)) { showCareNote = true }
                }
            }

            DisclaimerText()
        }
    }

    val profile = selected
    if (showTemp && profile != null) {
        LogTemperatureDialog(
            unit = unit,
            ageMonths = profile.ageMonthsAt(System.currentTimeMillis()),
            onDismiss = { showTemp = false },
            onConfirm = { celsius, site, note, at ->
                vm.logTemperature(celsius, site, note, at)
                showTemp = false
            }
        )
    }
    if (showDose) {
        LogDoseDialog(
            medications = medications.filter { it.active },
            onDismiss = { showDose = false },
            onConfirm = { medication, name, amount, doseUnit, note, at ->
                vm.logDose(medication, name, amount, doseUnit, note, at)
                showDose = false
            }
        )
    }
    if (showSymptom) {
        AddSymptomDialog(
            onDismiss = { showSymptom = false },
            onConfirm = { name, severity, note, startedAt ->
                vm.addSymptom(name, severity, note, startedAt)
                showSymptom = false
            }
        )
    }
    if (showCareNote) {
        CareNoteDialog(
            onDismiss = { showCareNote = false },
            onConfirm = { kind, text, at ->
                vm.addCareNote(kind, text, at)
                showCareNote = false
            }
        )
    }
    if (showStartEpisode) {
        StartEpisodeDialog(
            onDismiss = { showStartEpisode = false },
            onConfirm = { title, startedAt ->
                vm.startEpisode(title, startedAt)
                showStartEpisode = false
            }
        )
    }
}

/** The headline: the last temperature, what it means at this person's age, and how old it is. */
@Composable
private fun TemperatureCard(snapshot: ProfileSnapshot, unit: TempUnit) {
    val reading = snapshot.latestTemperature
    val assessment = snapshot.temperatureAssessment
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileDot(snapshot.profile, size = 40, selected = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(snapshot.profile.name, style = MaterialTheme.typography.titleMedium)
                    val age = snapshot.profile.ageLabelAt(System.currentTimeMillis())
                    val subtitle = listOfNotNull(snapshot.profile.relationship, age).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
                CareBadge(snapshot.careLevel)
            }

            if (reading == null || assessment == null) {
                Text("No temperature recorded yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    Temperature.format(reading.value, unit),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = careColor(assessment.careLevel)
                )
                Text(
                    "${assessment.band.label} · ${reading.site?.label ?: TempSite.ORAL.label} · " +
                        formatStamp(reading.takenAt),
                    style = MaterialTheme.typography.bodyMedium
                )
                assessment.reasons.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = careColor(assessment.careLevel))
                }
                snapshot.profile.baselineTempC?.let { baseline ->
                    Text(
                        "Their usual is ${Temperature.format(baseline, unit)} — " +
                            "this is ${Temperature.formatDelta(reading.value - baseline, unit)}.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** One medicine with the only fact that matters at 3am: can it be given yet, and if not, when. */
@Composable
private fun MedicationDueRow(status: MedicationStatus, onGive: (Medication) -> Unit) {
    val window = status.window
    val now = System.currentTimeMillis()
    val (label, color) = when (window.status) {
        DoseStatus.READY -> "Due now" to MaterialTheme.colorScheme.primary
        DoseStatus.WAIT -> "in ${DoseSchedule.formatDuration(window.waitMillis(now))}" to
            MaterialTheme.colorScheme.onSurfaceVariant
        DoseStatus.LIMIT_REACHED -> "Daily limit" to MaterialTheme.colorScheme.error
    }
    ListItem(
        headlineContent = { Text(status.medication.name) },
        supportingContent = { Text(window.reason, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            if (window.isReady) {
                Button(onClick = { onGive(status.medication) }) { Text("Give") }
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    )
}

@Composable
private fun QuickAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun StartEpisodeDialog(onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    var title by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start an illness") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What is it? (\"Flu\", \"Ear infection\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Backdating this is how an illness nobody recorded at the time gets entered at all
                // — and it is also what decides which records get adopted into it.
                WhenField(value = startedAt, onValueChange = { startedAt = it }, label = "Started")
                Text(
                    "Readings, doses and notes from the 12 hours before it started will be filed " +
                        "against it too — an illness is nearly always noticed after the first " +
                        "temperature was taken.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title.trim(), startedAt) }
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
