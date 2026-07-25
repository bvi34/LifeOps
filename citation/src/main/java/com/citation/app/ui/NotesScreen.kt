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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

/**
 * The Notes surface: every captured note, each showing its **degradation state** and — when it can —
 * a jump back to live context. The frozen snapshot is always shown, so even an orphaned or
 * source-unavailable note reads fully; the badge just tells you whether the jump is live, shaky, or
 * gone. This is where "a note outlives its source" is visible to the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notes yet. Highlight a passage while reading to capture one.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(notes, key = { it.key.toString() }) { note ->
                    NoteRow(note, vm)
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, vm: ReaderViewModel) {
    // Resolve the note's state off the UI thread; default to the most optimistic while loading.
    val state by produceState(initialValue = NoteResolver.State.RESOLVED, note) {
        value = vm.overallState(note)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { vm.jumpToNote(note) }
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
        Text(
            text = note.body,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = note.source.title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

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
