@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.FutureOperationStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.util.DateUtil
import com.lifeops.app.ui.components.BackNavIcon

/** Notes are posted as timestamped segments, the same journal shape as task notes — each
 *  entry is committed once via the composer at the bottom rather than edited in place. */
@Composable
fun FutureOperationDetailScreen(viewModel: FutureOperationDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val operation = state.operation

    var title by rememberSaveable(operation?.id) { mutableStateOf(operation?.title ?: "") }
    var newNoteText by rememberSaveable(operation?.id) { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Newest notes sit at the bottom next to the composer; keep them in view as they post.
    LaunchedEffect(state.notes.size) {
        if (state.notes.isNotEmpty()) listState.animateScrollToItem(state.notes.size - 1)
    }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    // Mirrors the Complete/Reactivate lifecycle on current operations: active
                    // ideas can be promoted into a real operation or shelved; archived ones restored.
                    if (operation != null) {
                        if (operation.status == FutureOperationStatus.ACTIVE) {
                            TextButton(onClick = { viewModel.promote() }) { Text("Promote") }
                            TextButton(onClick = { viewModel.setStatus(FutureOperationStatus.ARCHIVED) }) {
                                Text("Archive")
                            }
                        } else {
                            TextButton(onClick = { viewModel.setStatus(FutureOperationStatus.ACTIVE) }) {
                                Text("Restore")
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete operation")
                    }
                }
            )
        }
    ) { padding ->
        if (operation == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    viewModel.saveTitle(it)
                },
                label = { Text("Title") },
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (state.notes.isEmpty()) {
                Text(
                    "No notes yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(state.notes, key = { it.id }) { note ->
                        SelectionContainer {
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(note.content, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    DateUtil.localDateKey(note.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newNoteText,
                    onValueChange = { newNoteText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a note…") },
                    minLines = 1,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (newNoteText.isNotBlank()) {
                            viewModel.addNote(newNoteText.trim())
                            newNoteText = ""
                        }
                    },
                    enabled = newNoteText.isNotBlank()
                ) { Icon(Icons.Default.Add, "Add note") }
            }
        }
    }
}
