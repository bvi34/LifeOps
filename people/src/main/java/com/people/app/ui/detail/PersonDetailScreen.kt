package com.people.app.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.people.app.data.model.ImportantDate
import com.people.app.data.model.PartnerLink
import com.people.app.data.model.Person
import com.people.app.data.model.PersonNote
import com.people.app.data.prefs.PartnerPrefs
import com.people.app.data.repository.PartnerRepository
import com.people.app.data.repository.PartnerSyncService
import com.people.app.data.repository.PeopleRepository
import com.people.app.data.repository.PeopleSyncService
import com.people.app.logic.DateKind
import com.people.app.logic.ImportantDates
import com.people.app.partner.PartnerInvite
import com.people.app.partner.PartnerInviteCodec
import com.people.app.ui.common.DetailRow
import com.people.app.ui.common.HouseholdToggle
import com.people.app.ui.common.PersonDot
import com.people.app.ui.common.SectionCard
import com.people.app.ui.common.formatDay
import com.people.app.ui.partner.PartnerSection
import com.people.app.ui.partner.PartnerSectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PersonDetailViewModel(
    private val repo: PeopleRepository,
    private val syncService: PeopleSyncService,
    private val peers: List<String>,
    private val personId: String,
    private val partnerRepo: PartnerRepository,
    private val partnerSync: PartnerSyncService,
    private val partnerPrefs: PartnerPrefs
) : ViewModel() {

    val person: StateFlow<Person?> =
        repo.observePerson(personId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val notes: StateFlow<List<PersonNote>> =
        repo.observeNotes(personId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dates: StateFlow<List<ImportantDate>> =
        repo.observeDates(personId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(person: Person) = viewModelScope.launch {
        repo.updatePerson(person)
        // An edit here is an edit the other apps should see; publish it rather than waiting.
        runCatching { syncService.sync(peers) }
    }

    /**
     * Tick or un-tick them as a household member — the switch that decides whether Health keeps a
     * profile for them. Published immediately, because the whole point is that another app acts on
     * it; waiting for the next launch is how this ended up looking broken in the first place.
     */
    fun setHousehold(household: Boolean) = viewModelScope.launch {
        person.value?.let { repo.updatePerson(it.copy(household = household)) }
        runCatching { syncService.sync(peers) }
    }

    fun addNote(content: String) = viewModelScope.launch { repo.addNote(personId, content) }

    fun deleteNote(note: PersonNote) = viewModelScope.launch { repo.deleteNote(note.id) }

    fun addDate(label: String, kind: DateKind, monthDay: String, year: Int?) = viewModelScope.launch {
        repo.addDate(personId, label, kind, monthDay, year)
    }

    fun deleteDate(date: ImportantDate) = viewModelScope.launch { repo.deleteDate(date.id) }

    fun archive(archived: Boolean) = viewModelScope.launch {
        person.value?.let { repo.updatePerson(it.copy(archived = archived)) }
        runCatching { syncService.sync(peers) }
    }

    // --- the partner seam ---

    val partnerLink: StateFlow<PartnerLink?> =
        partnerRepo.observeLink(personId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _myCode = MutableStateFlow<String?>(null)
    val myCode: StateFlow<String?> = _myCode.asStateFlow()

    private val _partnerDisplayName = MutableStateFlow(partnerPrefs.displayName)
    val partnerDisplayName: StateFlow<String> = _partnerDisplayName.asStateFlow()

    private val _partnerMessage = MutableStateFlow<String?>(null)
    val partnerMessage: StateFlow<String?> = _partnerMessage.asStateFlow()

    private val _partnerSyncing = MutableStateFlow(false)
    val partnerSyncing: StateFlow<Boolean> = _partnerSyncing.asStateFlow()

    /**
     * Run a partner round from this screen, and say what it found.
     *
     * The seam otherwise only runs when the app comes to the foreground, which left the two moments
     * people actually want it — "have they scanned my code yet?" and "have they seen what I added?" —
     * answerable only by leaving the app and coming back. The round covers every pairing, not just
     * this one: there is one envelope per install, and writing it is what publishes any of them.
     */
    fun syncPartnerNow() = viewModelScope.launch {
        _partnerSyncing.value = true
        val outcome = runCatching { partnerSync.sync() }
        _partnerMessage.value = outcome.fold(
            onSuccess = { result ->
                when {
                    result.error != null -> "Sync had trouble: ${result.error}"
                    result.linksSynced == 0 ->
                        "Nothing paired yet. This device is set up — swap codes to connect."

                    result.changes > 0 ->
                        "Synced. ${result.changes} change" +
                            (if (result.changes == 1) "" else "s") + " came in."

                    else -> "Synced. Nothing new from them."
                }
            },
            onFailure = { "Sync failed: ${it.message ?: it::class.java.simpleName}" }
        )
        _partnerSyncing.value = false
    }

    /**
     * Build this person's code, minting our half of the pairing secret if it does not exist yet.
     *
     * The code is per-person rather than per-install: it is *this* pairing's half-secret, so showing
     * the same screen to two different people hands out two different codes and one cannot be used
     * to join a conversation meant for the other.
     */
    fun showMyCode() = viewModelScope.launch {
        val current = person.value ?: return@launch
        val link = partnerRepo.ensureLink(personId, current.name)
        _myCode.value = PartnerInviteCodec.encode(
            PartnerInvite(
                instanceId = partnerPrefs.instanceId,
                displayName = partnerPrefs.displayName,
                personKey = current.personKey,
                secret = link.mySecret,
                issuedAt = System.currentTimeMillis()
            )
        )
    }

    fun setPartnerDisplayName(name: String) {
        _partnerDisplayName.value = name
        partnerPrefs.displayName = name
        // Re-issue the code so the name on it is the one just typed rather than the one it was
        // built with — the QR is on screen while this field is being edited.
        if (_myCode.value != null) showMyCode()
    }

    /** Take a scanned or pasted code and complete our half of the pairing. */
    fun onCodeScanned(text: String) = viewModelScope.launch {
        val current = person.value ?: return@launch
        val invite = PartnerInviteCodec.decode(text)
        if (invite == null) {
            _partnerMessage.value = "That isn't a LifeOps pairing code."
            return@launch
        }
        when (val result = partnerRepo.acceptScan(personId, invite, partnerPrefs.instanceId)) {
            is PartnerRepository.ScanResult.Paired -> {
                _partnerMessage.value =
                    "Paired with ${result.link.partnerName.ifBlank { current.name }}. " +
                        "They need to scan your code too."
                runCatching { partnerSync.sync() }
            }

            is PartnerRepository.ScanResult.Refused -> {
                _partnerMessage.value = when (result.reason) {
                    PartnerRepository.ScanRefusal.OUR_OWN_CODE ->
                        "That's this app's own code — scan the other person's."

                    PartnerRepository.ScanRefusal.ALREADY_PAIRED_ELSEWHERE ->
                        "That app is already paired with ${result.existingPartnerName.orEmpty()}. " +
                            "Unlink them first."
                }
            }
        }
    }

    fun unlinkPartner() = viewModelScope.launch {
        partnerLink.value?.let { partnerRepo.unlink(it.id) }
        _myCode.value = null
        _partnerMessage.value = null
    }

    fun resetPairing() = viewModelScope.launch {
        partnerLink.value?.let { partnerRepo.resetPairing(it.id) }
        _myCode.value = null
        _partnerMessage.value = "Pairing reset. Swap codes again — both of you."
    }

    fun dismissPartnerMessage() {
        _partnerMessage.value = null
    }

    class Factory(
        private val repo: PeopleRepository,
        private val syncService: PeopleSyncService,
        private val peers: List<String>,
        private val personId: String,
        private val partnerRepo: PartnerRepository,
        private val partnerSync: PartnerSyncService,
        private val partnerPrefs: PartnerPrefs
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PersonDetailViewModel(
                repo, syncService, peers, personId, partnerRepo, partnerSync, partnerPrefs
            ) as T
    }
}

/** One person: who they are, the dates that come round, the notes, and the pairing with their app. */
@Composable
fun PersonDetailScreen(
    vm: PersonDetailViewModel,
    onBack: () -> Unit,
    onOpenPartnerWeek: () -> Unit
) {
    val person by vm.person.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val dates by vm.dates.collectAsStateWithLifecycle()
    val partnerLink by vm.partnerLink.collectAsStateWithLifecycle()
    val myCode by vm.myCode.collectAsStateWithLifecycle()
    val myName by vm.partnerDisplayName.collectAsStateWithLifecycle()
    val partnerMessage by vm.partnerMessage.collectAsStateWithLifecycle()
    val partnerSyncing by vm.partnerSyncing.collectAsStateWithLifecycle()

    var showEdit by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }

    val current = person
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PersonDot(current, size = 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current.name, style = MaterialTheme.typography.headlineSmall)
                current.relationship?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(onClick = onBack) { Text("Back") }
        }

        SectionCard(
            title = "Details",
            trailing = { TextButton(onClick = { showEdit = true }) { Text("Edit") } }
        ) {
            DetailRow("Birth date", current.birthDate)
            DetailRow("Email", current.email)
            DetailRow("Phone", current.phone)
            DetailRow("Note", current.note)
            if (listOfNotNull(current.birthDate, current.email, current.phone, current.note).isEmpty()) {
                Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Shared with the other apps. Their own additions — LifeOps' weather tolerances, " +
                    "Health's readings — stay with them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            HouseholdToggle(
                checked = current.household,
                onCheckedChange = { vm.setHousehold(it) }
            )
        }

        PartnerSection(
            state = PartnerSectionState(
                personName = current.name,
                link = partnerLink,
                myCode = myCode,
                myDisplayName = myName,
                message = partnerMessage,
                syncing = partnerSyncing
            ),
            onShowCode = vm::showMyCode,
            onSyncNow = vm::syncPartnerNow,
            onDisplayNameChange = vm::setPartnerDisplayName,
            onCodeScanned = vm::onCodeScanned,
            onOpenWeek = onOpenPartnerWeek,
            onUnlink = vm::unlinkPartner,
            onReset = vm::resetPairing,
            onDismissMessage = vm::dismissPartnerMessage
        )

        SectionCard(
            title = "Dates",
            trailing = { TextButton(onClick = { showDate = true }) { Text("Add") } }
        ) {
            if (dates.isEmpty() && current.birthDate == null) {
                Text(
                    "Birthdays, anniversaries, the yearly check-up — anything that comes round again.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            current.birthDate?.let { birthDate ->
                ImportantDates.monthDayOf(birthDate)?.let { monthDay ->
                    val next = ImportantDates.nextOccurrence(monthDay, java.time.LocalDate.now())
                    Text(
                        "Birthday · ${next?.let { formatDayOfYear(it) } ?: birthDate} " +
                            "(from their birth date)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            dates.forEach { date ->
                ListItem(
                    headlineContent = { Text(date.label) },
                    supportingContent = {
                        val next = ImportantDates.nextOccurrence(date.monthDay, java.time.LocalDate.now())
                        Text(
                            listOfNotNull(
                                date.kind.label,
                                next?.let { formatDayOfYear(it) },
                                date.year?.let { "since $it" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { vm.deleteDate(date) }) { Text("Remove") }
                    }
                )
            }
        }

        SectionCard(title = "Notes") {
            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                label = { Text("Add a note") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = noteDraft.isNotBlank(),
                    onClick = {
                        vm.addNote(noteDraft)
                        noteDraft = ""
                    }
                ) { Text("Save note") }
            }
            if (notes.isEmpty()) {
                Text(
                    "Notes stay in People — they are not published over the sync seam.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            notes.forEach { note ->
                ListItem(
                    headlineContent = { Text(note.content) },
                    supportingContent = { Text(formatDay(note.createdAt), style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { TextButton(onClick = { vm.deleteNote(note) }) { Text("Delete") } }
                )
            }
        }

        OutlinedButton(
            onClick = { vm.archive(!current.archived) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (current.archived) "Bring back into the household" else "Archive")
        }
        Text(
            "Archiving withdraws them from the household lists and tells the other apps to do the " +
                "same. Nothing is erased — on either side.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showEdit) {
        EditPersonDialog(
            person = current,
            onDismiss = { showEdit = false },
            onConfirm = {
                vm.update(it)
                showEdit = false
            }
        )
    }
    if (showDate) {
        AddDateDialog(
            onDismiss = { showDate = false },
            onConfirm = { label, kind, monthDay, year ->
                vm.addDate(label, kind, monthDay, year)
                showDate = false
            }
        )
    }
}

private fun formatDayOfYear(date: java.time.LocalDate): String =
    "${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"

@Composable
private fun EditPersonDialog(person: Person, onDismiss: () -> Unit, onConfirm: (Person) -> Unit) {
    var name by remember { mutableStateOf(person.name) }
    var relationship by remember { mutableStateOf(person.relationship.orEmpty()) }
    var birthDate by remember { mutableStateOf(person.birthDate.orEmpty()) }
    var email by remember { mutableStateOf(person.email.orEmpty()) }
    var phone by remember { mutableStateOf(person.phone.orEmpty()) }
    var note by remember { mutableStateOf(person.note.orEmpty()) }

    val birthDateValid = birthDate.isBlank() || ImportantDates.parseIso(birthDate) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${person.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(relationship, { relationship = it }, label = { Text("Who they are") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = birthDate,
                    onValueChange = { birthDate = it },
                    label = { Text("Birth date (YYYY-MM-DD)") },
                    isError = !birthDateValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && birthDateValid,
                onClick = {
                    onConfirm(
                        person.copy(
                            name = name.trim(),
                            relationship = relationship.ifBlank { null },
                            birthDate = birthDate.ifBlank { null },
                            email = email.ifBlank { null },
                            phone = phone.ifBlank { null },
                            note = note.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddDateDialog(
    onDismiss: () -> Unit,
    onConfirm: (label: String, kind: DateKind, monthDay: String, year: Int?) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(DateKind.BIRTHDAY) }
    var monthDay by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }

    val monthDayValid = ImportantDates.parseMonthDay(monthDay) != null
    val yearValue = year.toIntOrNull()
    val yearValid = year.isBlank() || (yearValue != null && yearValue in 1900..2200)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A date that comes round") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("What is it?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateKind.entries.forEach { option ->
                        FilterChip(
                            selected = option == kind,
                            onClick = { kind = option },
                            label = { Text(option.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = monthDay,
                    onValueChange = { monthDay = it },
                    label = { Text("Day (MM-DD)") },
                    isError = monthDay.isNotBlank() && !monthDayValid,
                    supportingText = { Text("29 February is fine — it lands on the 28th in other years.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("First year (optional)") },
                    isError = !yearValid,
                    supportingText = { Text("Lets People say which anniversary it is.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && monthDayValid && yearValid,
                onClick = { onConfirm(label.trim(), kind, monthDay.trim(), yearValue) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
