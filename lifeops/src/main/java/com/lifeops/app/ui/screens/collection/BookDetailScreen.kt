@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.BookNote
import com.lifeops.app.data.model.BookStatus
import com.lifeops.app.data.model.BookTimeEntry
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

@Composable
fun BookDetailScreen(viewModel: BookDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val book = state.book

    Scaffold(topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }) { padding ->
        if (book == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        book.author?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        // The Citation record: source + its own id, so O'Reilly's identity is visible
                        // and preserved even after the GUI title above is renamed. Only for synced books.
                        book.sourceType?.let { source ->
                            val idPart = book.sourceId?.let { " · $it" } ?: ""
                            Text(
                                "Citation: ${citationSourceLabel(source)}$idPart",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // When the user has renamed the book, show what Citation still calls it.
                        book.citationTitle?.takeIf { it != book.title }?.let {
                            Text(
                                "Synced as “$it”",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.showEditDialog() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit book")
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete book")
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BookStatus.entries.forEachIndexed { index, status ->
                        SegmentedButton(
                            selected = book.status == status,
                            onClick = { viewModel.setStatus(status) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = BookStatus.entries.size)
                        ) { Text(status.label()) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Time logged: ${state.totalMinutes} min",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.showAddTimeDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Log time")
                    }
                }
            }
            items(state.timeEntries, key = { it.id }) { entry ->
                TimeEntryRow(entry, onDelete = { viewModel.deleteTimeEntry(entry.id) })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.showAddNoteDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add note")
                    }
                }
            }
            items(state.notes, key = { it.id }) { note ->
                NoteRow(note, onDelete = { viewModel.deleteNote(note.id) })
            }
        }
    }

    if (state.showEditDialog && book != null) {
        EditBookDialog(
            initialTitle = book.title,
            initialAuthor = book.author ?: "",
            onDismiss = { viewModel.hideEditDialog() },
            onConfirm = { title, author -> viewModel.update(title, author) }
        )
    }
    if (state.showAddNoteDialog) {
        AddNoteDialog(
            onDismiss = { viewModel.hideAddNoteDialog() },
            onConfirm = { content -> viewModel.addNote(content) }
        )
    }
    if (state.showAddTimeDialog) {
        AddTimeDialog(
            onDismiss = { viewModel.hideAddTimeDialog() },
            onConfirm = { minutes, note -> viewModel.addTimeEntry(minutes, note) }
        )
    }
}

private fun BookStatus.label() = when (this) {
    BookStatus.TO_READ -> "To read"
    BookStatus.READING -> "Reading"
    BookStatus.DONE -> "Done"
}

/** Friendly label for a Citation `SourceType` name; unknown values pass through as-is. */
private fun citationSourceLabel(sourceType: String) = when (sourceType.uppercase()) {
    "OREILLY" -> "O'Reilly"
    "ROYAL_ROAD" -> "Royal Road"
    "EPUB" -> "EPUB"
    "PDF" -> "PDF"
    "KINDLE" -> "Kindle"
    "INTERNAL" -> "Citation"
    else -> sourceType
}

@Composable
private fun TimeEntryRow(entry: BookTimeEntry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${entry.durationMinutes} min", style = MaterialTheme.typography.bodyMedium)
                entry.note?.let {
                    SelectionContainer {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete time entry") }
        }
    }
}

@Composable
private fun NoteRow(note: BookNote, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(note.content, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete note") }
        }
    }
}

@Composable
private fun EditBookDialog(
    initialTitle: String,
    initialAuthor: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String?) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var author by rememberSaveable { mutableStateOf(initialAuthor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim(), author.trim().ifBlank { null }) },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onConfirm: (content: String) -> Unit) {
    var content by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(content.trim()) }, enabled = content.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddTimeDialog(onDismiss: () -> Unit, onConfirm: (minutes: Int, note: String?) -> Unit) {
    var minutesText by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val minutes = minutesText.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { v -> minutesText = v.filter { it.isDigit() } },
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
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
            TextButton(onClick = { onConfirm(minutes, note.trim().ifBlank { null }) }, enabled = minutes > 0) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
