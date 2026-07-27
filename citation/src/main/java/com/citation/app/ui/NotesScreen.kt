package com.citation.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.NoteType
import java.text.DateFormat
import java.util.Date

/**
 * The Notes list: every captured note, each showing its **degradation state** and — when it can —
 * a jump back to live context. The frozen snapshot is always shown, so even an orphaned or
 * source-unavailable note reads fully; the badge just tells you whether the jump is live, shaky, or
 * gone. This is where "a note outlives its source" is visible to the reader. Rendered content-only so
 * the Personal tab can host it beneath its reading stats.
 */
@Composable
fun NotesList(vm: ReaderViewModel, modifier: Modifier = Modifier) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    // The note currently open in the detail/edit sheet (tap a row to open).
    var editing by remember { mutableStateOf<Note?>(null) }

    if (notes.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No notes yet. Highlight a passage while reading, or use “Save to Citation” from any app.",
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(24.dp)
            )
        }
    } else {
        LazyColumn(modifier.fillMaxSize()) {
            items(notes, key = { it.key.toString() }) { note ->
                NoteRow(note, vm, onClick = { editing = note })
            }
        }
    }

    editing?.let { note ->
        NoteDetailDialog(
            note = note,
            onSave = { body -> vm.editNote(note.key.toString(), body); editing = null },
            onJump = { vm.jumpToNote(note); editing = null },
            onDelete = { vm.deleteNote(note.key.toString()); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun NoteRow(note: Note, vm: ReaderViewModel, onClick: () -> Unit) {
    // Resolve the note's state off the UI thread; default to the most optimistic while loading.
    val state by produceState(initialValue = NoteResolver.State.RESOLVED, note) {
        value = vm.overallState(note)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (note.type == NoteType.PASSAGE_ANCHORED) "Passage note" else "Synthesis",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
            StateBadge(state)
        }
        // The frozen snapshot — always readable, source or no source.
        note.references.firstOrNull()?.let { ref ->
            Text(
                text = "“${ref.quotedSnapshot}”",
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (note.body.isNotBlank()) {
            Text(
                text = note.body,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        } else {
            Text(
                text = "Tap to add a note",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "${note.source.title} · ${formatWhen(note.createdAt)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * Open one note to read it in full and **add your own words**. A captured quote arrives with an empty
 * body; this is where you annotate it. When the note is bound to a source, a "Jump to source" button
 * appears; otherwise you just read the frozen snapshot and write your note.
 */
@Composable
fun NoteDetailDialog(
    note: Note,
    onSave: (String) -> Unit,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var body by remember(note.key) { mutableStateOf(note.body) }
    val canJump = note.source.bookKey != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(note.source.title) },
        text = {
            Column {
                note.references.firstOrNull()?.let { ref ->
                    Text(
                        "“${ref.quotedSnapshot}”",
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "Captured ${formatWhen(note.createdAt)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Your note") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (canJump) {
                        TextButton(onClick = onJump) { Text("Jump to source") }
                    }
                    onDelete?.let {
                        TextButton(onClick = it) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(body) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** A friendly absolute date+time for when a note was captured. */
private fun formatWhen(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

@Composable
private fun StateBadge(state: NoteResolver.State) {
    val (label, color) = when (state) {
        NoteResolver.State.RESOLVED -> "Linked" to Color(0xFF2E7D32)
        NoteResolver.State.FUZZY -> "Shifted" to Color(0xFFF9A825)
        NoteResolver.State.ORPHANED -> "Orphaned" to Color(0xFFB0653B)
        NoteResolver.State.SOURCE_UNAVAILABLE -> "Source gone" to Color(0xFF6D6D6D)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
