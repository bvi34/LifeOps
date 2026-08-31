package com.people.app.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.people.app.data.model.CheckIn
import com.people.app.data.model.CheckInField
import com.people.app.data.repository.CheckInRepository
import com.people.app.data.repository.PeopleRepository
import com.people.app.logic.CheckInKind
import com.people.app.logic.CheckIns
import com.people.app.ui.common.SectionCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * One person's daily check-in: the day being filled in, the form behind it, and what the last
 * fortnight said.
 *
 * The screen is one destination with two modes rather than two destinations, because designing the
 * form and filling it in are the same activity a minute apart — the first time somebody uses this
 * they will add a question, answer it, and want another one, and a round trip through the person's
 * page between each is how a form ends up half-built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModel(
    private val repo: CheckInRepository,
    peopleRepo: PeopleRepository,
    private val personId: String
) : ViewModel() {

    val personName: StateFlow<String> = peopleRepo.observePerson(personId)
        .map { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * The day being filled in.
     *
     * Movable, and bounded at today. An evening habit is missed in the evening: "yesterday" is the
     * single most useful thing this screen can offer somebody who remembered at breakfast, and a
     * check-in dated tomorrow is not a thing that can honestly exist.
     */
    private val _day = MutableStateFlow(LocalDate.now())
    val day: StateFlow<LocalDate> = _day.asStateFlow()

    val form: StateFlow<List<CheckInField>> = repo.observeForm(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every question including the retired ones — the editor's list, not the form's. */
    val allFields: StateFlow<List<CheckInField>> = repo.observeFields(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checkIn: StateFlow<CheckIn?> = _day
        .flatMapLatest { repo.observeCheckIn(personId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent: StateFlow<List<CheckIn>> = repo.observeRecent(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What the controls hold, keyed by question.
     *
     * Held here rather than in each control's `remember` so that stepping to another day, or saving,
     * replaces the whole form's contents in one move — a `remember` keyed by field id would keep
     * yesterday's typing on screen under today's date.
     */
    private val _draft = MutableStateFlow<Map<String, String>>(emptyMap())
    val draft: StateFlow<Map<String, String>> = _draft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Whether the person has typed since the draft was last seeded.
     *
     * The seeding runs again every time the stored day re-emits — it has to, because Room answers
     * with null before it answers with the row, and a draft seeded once from that first null would
     * show an empty form for a day that *is* recorded and then overwrite it on save. This flag is
     * what stops the same re-seeding from wiping what somebody is in the middle of typing.
     */
    private var edited = false

    /** The day the draft currently reflects — null until the first seed for a day has happened. */
    private val _loadedDay = MutableStateFlow<LocalDate?>(null)
    val loadedDay: StateFlow<LocalDate?> = _loadedDay.asStateFlow()

    fun openDay(day: LocalDate) {
        if (day.isAfter(LocalDate.now())) return
        edited = false
        _day.value = day
        _loadedDay.value = null
    }

    fun stepDay(days: Long) = openDay(_day.value.plusDays(days))

    /** Seed the controls from what that day already says — an edit, not a fresh page. */
    fun syncDraft() {
        val day = _day.value
        if (edited && _loadedDay.value == day) return
        _draft.value = checkIn.value
            ?.takeIf { it.day == day }
            ?.answers
            ?.associate { it.fieldId to it.value }
            .orEmpty()
        _loadedDay.value = day
    }

    fun setValue(fieldId: String, value: String) {
        edited = true
        _draft.value = _draft.value + (fieldId to value)
    }

    fun save() = viewModelScope.launch {
        val day = _day.value
        val recorded = repo.save(personId, day, _draft.value)
        // Let the stored day seed the controls again: what came back is what was actually kept,
        // including a number that would not parse and is therefore not there.
        edited = false
        _message.value = if (recorded) {
            "${CheckIns.describeDay(day)}'s check-in saved."
        } else {
            "Nothing filled in — ${CheckIns.describeDay(day)} was left unrecorded."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    // --- the form editor ---

    fun addField(label: String, kind: CheckInKind, options: List<String>) = viewModelScope.launch {
        repo.addField(personId, label, kind, options)
    }

    fun addStarterForm() = viewModelScope.launch { repo.addStarterForm(personId) }

    fun updateField(id: String, label: String, kind: CheckInKind, options: List<String>) =
        viewModelScope.launch { repo.updateField(id, label, kind, options) }

    fun removeField(id: String) = viewModelScope.launch {
        val deleted = repo.removeField(id)
        _message.value = if (deleted) {
            "Question removed."
        } else {
            "Taken off the form. The answers it already collected are kept."
        }
        _draft.value = _draft.value - id
    }

    fun restoreField(id: String) = viewModelScope.launch { repo.restoreField(id) }

    fun moveField(id: String, up: Boolean) = viewModelScope.launch { repo.moveField(id, up) }

    /** Asked before the editor opens on a question, so a fixed kind is shown as fixed. */
    suspend fun canRetype(id: String): Boolean = repo.canRetype(id)

    class Factory(
        private val repo: CheckInRepository,
        private val peopleRepo: PeopleRepository,
        private val personId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CheckInViewModel(repo, peopleRepo, personId) as T
    }
}

@Composable
fun CheckInScreen(vm: CheckInViewModel, onBack: () -> Unit) {
    val personName by vm.personName.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val allFields by vm.allFields.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loadedDay by vm.loadedDay.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }

    // Seed the controls when the day changes and again when its stored answers arrive, since the
    // first emission for a recorded day is a null on the way to the row. The view model keeps the
    // re-seed from touching anything already typed.
    LaunchedEffect(day, checkIn) { vm.syncDraft() }

    val today = LocalDate.now()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (personName.isBlank()) "Check-in" else "$personName's check-in",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Recorded here and nowhere else — check-ins stay in People.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }

            item(key = "day") {
                DayPicker(
                    day = day,
                    today = today,
                    recorded = checkIn != null,
                    onStep = vm::stepDay,
                    onToday = { vm.openDay(today) }
                )
            }

            message?.let { text ->
                item(key = "message") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = vm::dismissMessage) { Text("OK") }
                    }
                }
            }

            if (editing) {
                item(key = "editor") {
                    FormEditor(
                        fields = allFields,
                        onAdd = vm::addField,
                        onUpdate = vm::updateField,
                        onRemove = vm::removeField,
                        onRestore = vm::restoreField,
                        onMove = vm::moveField,
                        canRetype = vm::canRetype,
                        onDone = { editing = false }
                    )
                }
            } else if (form.isEmpty()) {
                item(key = "empty-form") {
                    SectionCard(title = "No form yet") {
                        Text(
                            "A check-in is a few questions you write once and answer each day — " +
                                "what they had for lunch, the activity they enjoyed, how the day " +
                                "went. Nobody else's form has to look like yours.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.addStarterForm() }) { Text("Start from a simple form") }
                            OutlinedButton(onClick = { editing = true }) { Text("Write my own") }
                        }
                    }
                }
            } else {
                item(key = "form-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            CheckIns.describeDay(day, today),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editing = true }) { Text("Edit form") }
                    }
                }
                items(form, key = { it.id }) { field ->
                    FieldControl(
                        field = field,
                        value = draft[field.id].orEmpty(),
                        onValueChange = { vm.setValue(field.id, it) }
                    )
                }
                item(key = "save") {
                    Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (checkIn == null) "Save check-in" else "Update check-in")
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "recent-header") {
                    Text("Recent days", style = MaterialTheme.typography.titleSmall)
                }
                items(recent, key = { it.id }) { entry ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    CheckIns.describeDay(entry.day, today),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.openDay(entry.day) }) { Text("Open") }
                            }
                            entry.answers.forEach { answer ->
                                Text(
                                    "${answer.label}: ${answer.display}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which day is being recorded.
 *
 * A stepper rather than a calendar: the days anybody fills in are today and the two or three behind
 * it, and a date picker for "yesterday" is three taps where one will do. Forward stops at today.
 */
@Composable
private fun DayPicker(
    day: LocalDate,
    today: LocalDate,
    recorded: Boolean,
    onStep: (Long) -> Unit,
    onToday: () -> Unit
) {
    SectionCard(title = "Day") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onStep(-1) }) { Text("◀ Earlier") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(CheckIns.describeDay(day, today), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (recorded) "Recorded — editing it" else "Not recorded yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onStep(1) }, enabled = day.isBefore(today)) { Text("Later ▶") }
        }
        if (day != today) {
            AssistChip(onClick = onToday, label = { Text("Back to today") })
        }
    }
}

/** One question's control. The kind decides the control; nothing else about the screen changes. */
@Composable
private fun FieldControl(field: CheckInField, value: String, onValueChange: (String) -> Unit) {
    SectionCard(title = field.label) {
        when (field.kind) {
            CheckInKind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            CheckInKind.NOTE -> OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            CheckInKind.NUMBER -> OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = value.isNotBlank() && value.toDoubleOrNull() == null,
                modifier = Modifier.fillMaxWidth()
            )

            CheckInKind.YES_NO -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    when (value) {
                        CheckIns.YES -> "Yes"
                        CheckIns.NO -> "No"
                        else -> "Not answered"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                // Three states, not two: a switch alone cannot say "not answered", and a day nobody
                // touched must not read as a No.
                Switch(
                    checked = value == CheckIns.YES,
                    onCheckedChange = { onValueChange(if (it) CheckIns.YES else CheckIns.NO) }
                )
                if (value.isNotBlank()) {
                    TextButton(onClick = { onValueChange("") }) { Text("Clear") }
                }
            }

            CheckInKind.SCALE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (CheckIns.SCALE_MIN..CheckIns.SCALE_MAX).forEach { step ->
                    FilterChip(
                        selected = value == step.toString(),
                        onClick = { onValueChange(if (value == step.toString()) "" else step.toString()) },
                        label = { Text("$step") }
                    )
                }
            }

            CheckInKind.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (field.options.isEmpty()) {
                    Text(
                        "No options set yet — add some in Edit form.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.options.forEach { option ->
                        FilterChip(
                            selected = value == option,
                            onClick = { onValueChange(if (value == option) "" else option) },
                            label = { Text(option) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The form, being designed.
 *
 * Retired questions are listed below the live ones rather than hidden, because "where did Lunch go?"
 * is the first thing somebody asks after removing one, and the answer — it is off the form and its
 * answers are safe — is only reassuring if they can see it.
 */
@Composable
private fun FormEditor(
    fields: List<CheckInField>,
    onAdd: (String, CheckInKind, List<String>) -> Unit,
    onUpdate: (String, String, CheckInKind, List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onRestore: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    canRetype: suspend (String) -> Boolean,
    onDone: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<CheckInField?>(null) }

    val live = fields.filterNot { it.retired }
    val retired = fields.filter { it.retired }

    SectionCard(
        title = "The form",
        trailing = { TextButton(onClick = onDone) { Text("Done") } }
    ) {
        Text(
            "The questions asked every day. Rename them whenever you like — a fix shows up on every " +
                "day they have already recorded.",
            style = MaterialTheme.typography.bodySmall
        )

        live.forEachIndexed { index, field ->
            ListItem(
                headlineContent = { Text(field.label) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            field.kind.label,
                            field.options.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMove(field.id, true) }, enabled = index > 0) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = { onMove(field.id, false) },
                            enabled = index < live.lastIndex
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                        }
                        TextButton(onClick = { editingField = field }) { Text("Edit") }
                    }
                }
            )
        }

        if (live.isEmpty()) {
            Text("No questions yet.", style = MaterialTheme.typography.bodySmall)
        }

        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add a question")
        }

        if (retired.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Off the form — their past answers are kept",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            retired.forEach { field ->
                ListItem(
                    headlineContent = { Text(field.label) },
                    supportingContent = { Text(field.kind.label, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        TextButton(onClick = { onRestore(field.id) }) { Text("Put back") }
                    }
                )
            }
        }
    }

    if (adding) {
        FieldDialog(
            field = null,
            kindFixed = false,
            onDismiss = { adding = false },
            onConfirm = { label, kind, options ->
                onAdd(label, kind, options)
                adding = false
            },
            onRemove = null
        )
    }
    editingField?.let { field ->
        // Asked live rather than carried on the model: whether a question can still be retyped
        // changes the moment somebody answers it, and a list built a second ago would not know.
        var kindFixed by remember(field.id) { mutableStateOf(true) }
        LaunchedEffect(field.id) { kindFixed = !canRetype(field.id) }

        FieldDialog(
            field = field,
            kindFixed = kindFixed,
            onDismiss = { editingField = null },
            onConfirm = { label, kind, options ->
                onUpdate(field.id, label, kind, options)
                editingField = null
            },
            onRemove = {
                onRemove(field.id)
                editingField = null
            }
        )
    }
}

/** Add or edit one question. */
@Composable
private fun FieldDialog(
    field: CheckInField?,
    kindFixed: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, CheckInKind, List<String>) -> Unit,
    onRemove: (() -> Unit)?
) {
    var label by remember { mutableStateOf(field?.label.orEmpty()) }
    var kind by remember { mutableStateOf(field?.kind ?: CheckInKind.TEXT) }
    var options by remember { mutableStateOf(field?.options?.joinToString(", ").orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (field == null) "A question to ask each day" else "Edit question") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Question") },
                    placeholder = { Text("Lunch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Answered with", style = MaterialTheme.typography.bodySmall)
                CheckInKind.entries.forEach { option ->
                    FilterChip(
                        selected = option == kind,
                        onClick = { if (!kindFixed) kind = option },
                        enabled = !kindFixed || option == kind,
                        label = { Text(option.label) }
                    )
                }
                Text(
                    if (kindFixed) {
                        "This question has answers already, so it keeps the kind it was asked with — " +
                            "reading them back another way would misrepresent what was recorded."
                    } else {
                        kind.hint
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (kind == CheckInKind.CHOICE) {
                    OutlinedTextField(
                        value = options,
                        onValueChange = { options = it },
                        label = { Text("Options, separated by commas") },
                        placeholder = { Text("School, Packed, Home") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                onRemove?.let { remove ->
                    TextButton(onClick = remove) { Text("Take off the form") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank(),
                onClick = {
                    onConfirm(
                        label.trim(),
                        kind,
                        options.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
