@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.PersonNote
import com.lifeops.app.data.model.Relationship
import com.lifeops.app.data.model.SunSensitivity
import com.lifeops.app.data.model.Task
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.BusyBlockEditorDialog
import com.lifeops.app.ui.components.BusyBlockRow
import com.lifeops.app.util.DateUtil

@Composable
fun PersonDetailScreen(
    viewModel: PersonDetailViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val person = state.person

    var editing by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var addingBlock by remember { mutableStateOf(false) }
    var editingBlock by remember { mutableStateOf<BusyBlock?>(null) }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        if (person == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ProfileCard(person, onEdit = { editing = true }) }

            item {
                SectionHeader("Weather comfort")
                PreferencesCard(person)
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Involved tasks")
                    TextButton(onClick = { attaching = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Attach")
                    }
                }
            }
            if (state.involvedTasks.isEmpty()) {
                item { EmptyLine("No tasks marked as involving ${person.name} yet.") }
            } else {
                items(state.involvedTasks, key = { it.id }) { task ->
                    InvolvedTaskRow(task, onRemove = { viewModel.detachTask(task.id) })
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Schedule")
                    TextButton(onClick = { addingBlock = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
            item {
                EmptyLine("Busy times block ${person.name} from outdoor-task suggestions during those hours.")
            }
            if (state.schedule.isEmpty()) {
                item { EmptyLine("No busy times yet.") }
            } else {
                items(state.schedule, key = { it.id }) { block ->
                    BusyBlockRow(
                        block = block,
                        onEdit = { editingBlock = block },
                        onDelete = { viewModel.deleteBusyBlock(block.id) }
                    )
                }
            }

            item { SectionHeader("Notes") }
            item { AddNoteField(onAdd = { viewModel.addNote(it) }) }
            if (state.notes.isEmpty()) {
                item { EmptyLine("No notes yet.") }
            } else {
                items(state.notes, key = { it.id }) { note ->
                    NoteRow(note, onDelete = { viewModel.deleteNote(note.id) })
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete ${person.name}")
                }
            }
        }
    }

    if (editing && person != null) {
        ProfileEditorDialog(
            person = person,
            onSave = { name, heat, cold, uv, wind, rain, sun, prefs, relationship ->
                viewModel.saveProfile(name, heat, cold, uv, wind, rain, sun, prefs, relationship)
                editing = false
            },
            onDismiss = { editing = false }
        )
    }

    if (attaching && person != null) {
        val involvedIds = state.involvedTasks.map { it.id }.toSet()
        AttachTaskDialog(
            candidates = state.weekTasks.filter { it.id !in involvedIds },
            onAttach = { taskId -> viewModel.attachTask(taskId) },
            onDismiss = { attaching = false }
        )
    }

    if ((addingBlock || editingBlock != null) && person != null) {
        BusyBlockEditorDialog(
            existing = editingBlock,
            personId = person.id,
            onSave = { block ->
                viewModel.saveBusyBlock(block)
                addingBlock = false; editingBlock = null
            },
            onDismiss = { addingBlock = false; editingBlock = null }
        )
    }

    if (confirmingDelete && person != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${person.name}?") },
            text = { Text("This removes the person, their notes, and their task links. Tasks themselves are not deleted.") },
            confirmButton = {
                Button(
                    onClick = { confirmingDelete = false; viewModel.delete(onDeleted = onBack) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
private fun ProfileCard(person: Person, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(person.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    person.relationship?.let {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, enabled = false, label = { Text(it.label) })
                    }
                }
                Text(
                    "Sun sensitivity: ${person.sunSensitivity.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (!person.activityPreferences.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(person.activityPreferences!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit profile") }
        }
    }
}

@Composable
private fun PreferencesCard(person: Person) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreferenceRow("Max feels-like", person.heatToleranceMaxF?.let { "$it °F" })
            PreferenceRow("Min feels-like", person.coldToleranceMinF?.let { "$it °F" })
            PreferenceRow("Max UV index", person.uvMax?.toString())
            PreferenceRow("Max wind", person.windMaxMph?.let { "$it mph" })
            PreferenceRow("Max rain chance", person.maxPrecipitationPct?.let { "$it %" })
        }
    }
}

@Composable
private fun PreferenceRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun InvolvedTaskRow(task: Task, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
        }
    }
}

@Composable
private fun NoteRow(note: PersonNote, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                Text(
                    DateUtil.formatDate(DateUtil.localDateKey(note.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Close, contentDescription = "Delete note") }
        }
    }
}

@Composable
private fun AddNoteField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Add a note") },
            modifier = Modifier.weight(1f),
            maxLines = 3
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { if (text.isNotBlank()) { onAdd(text.trim()); text = "" } }, enabled = text.isNotBlank()) {
            Text("Add")
        }
    }
}

@Composable
private fun AttachTaskDialog(
    candidates: List<Task>,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach a task") },
        text = {
            if (candidates.isEmpty()) {
                Text("No more tasks this week to attach.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.id }) { task ->
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAttach(task.id); onDismiss() }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Full profile editor: name, five optional weather ceilings, sun sensitivity, activity note. */
@Composable
private fun ProfileEditorDialog(
    person: Person,
    onSave: (
        name: String,
        heatMax: Int?, coldMin: Int?, uvMax: Int?, windMax: Int?, rainMax: Int?,
        sun: SunSensitivity, prefs: String?, relationship: Relationship?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(person.name) }
    var heat by remember { mutableStateOf(person.heatToleranceMaxF?.toString() ?: "") }
    var cold by remember { mutableStateOf(person.coldToleranceMinF?.toString() ?: "") }
    var uv by remember { mutableStateOf(person.uvMax?.toString() ?: "") }
    var wind by remember { mutableStateOf(person.windMaxMph?.toString() ?: "") }
    var rain by remember { mutableStateOf(person.maxPrecipitationPct?.toString() ?: "") }
    var sun by remember { mutableStateOf(person.sunSensitivity) }
    var prefs by remember { mutableStateOf(person.activityPreferences ?: "") }
    var relationship by remember { mutableStateOf(person.relationship) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text("Weather comfort (leave blank for no limit)", style = MaterialTheme.typography.labelMedium)
                NumberField("Max feels-like (°F)", heat) { heat = it }
                NumberField("Min feels-like (°F)", cold) { cold = it }
                NumberField("Max UV index", uv) { uv = it }
                NumberField("Max wind (mph)", wind) { wind = it }
                NumberField("Max rain chance (%)", rain) { rain = it }

                Text("Sun sensitivity", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SunSensitivity.entries.forEach { s ->
                        FilterChip(selected = sun == s, onClick = { sun = s }, label = { Text(s.label) })
                    }
                }

                Text(
                    "Relationship (for booking-balance nudges; optional)",
                    style = MaterialTheme.typography.labelMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Relationship.entries.forEach { r ->
                        FilterChip(
                            selected = relationship == r,
                            onClick = { relationship = if (relationship == r) null else r },
                            label = { Text(r.label) }
                        )
                    }
                }

                OutlinedTextField(
                    value = prefs, onValueChange = { prefs = it },
                    label = { Text("Activity preferences (optional)") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        heat.toIntOrNull(), cold.toIntOrNull(), uv.toIntOrNull(),
                        wind.toIntOrNull(), rain.toIntOrNull(), sun,
                        prefs.trim().ifBlank { null }, relationship
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
