package com.repository.app.ui.attach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.backupkit.AppId
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Shelf
import kotlinx.coroutines.launch

/**
 * Attach something already on the shelf to this record.
 *
 * This is the second half of a targeted import and the reason it is worth having one. A household
 * grabs six project docs off OneDrive onto the shelf, then opens the project and says *those three
 * are this project's*. The alternative — importing into the project directly, then importing the
 * same file again for the other thing it also belongs to — is how one document becomes three copies
 * that then disagree.
 *
 * **Nothing is copied here.** Attaching re-files the row: the same document, in a different drawer,
 * with the same bytes behind it. Which is why it is offered as *attach* rather than *add*, and why
 * a document can be sent back to the household's drawer just as easily.
 *
 * Documents another app is only lending are not offered. Their app has rules about where they live
 * — Health deletes a person's documents with the person — and re-filing one here would either
 * duplicate those rules or break them. See `source/DocumentSource`.
 */
@Composable
fun AttachFromShelfDialog(
    appKey: String,
    recordKey: String,
    recordLabel: String,
    onDismiss: () -> Unit,
    onAttached: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    val everything by remember { shelf.documents.observeShelf() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Everything this app could take: its own rows, minus the ones already on this record, minus
    // anything another app is lending.
    val offered = remember(everything, recordKey, appKey, query) {
        val candidates = everything.filterNot { document ->
            document.isForeign ||
                (document.owner.appKey == appKey && document.owner.recordKey == recordKey)
        }
        Shelf.search(candidates, query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach to $recordLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (everything.isEmpty()) {
                    Text(
                        "The shelf is empty. File something here, or get documents from a drive, " +
                            "and they can be attached afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Column
                }

                Text(
                    "These stay one document. Attaching moves it into this drawer — it is not copied, " +
                        "and it is still findable from the shelf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search the shelf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (offered.isEmpty()) {
                    Text(
                        "Nothing on the shelf matches that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(offered, key = { it.id }) { document ->
                            OfferedRow(
                                document = document,
                                checked = document.id in picked,
                                onToggle = {
                                    picked = if (document.id in picked) picked - document.id
                                    else picked + document.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    val ids = picked.toList()
                    onDismiss()
                    scope.launch {
                        ids.forEach { id ->
                            shelf.documents.refile(id, DocumentOwner(appKey, recordKey, recordLabel))
                        }
                        onAttached(ids.size)
                    }
                }
            ) {
                Text(if (picked.size <= 1) "Attach" else "Attach ${picked.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One document the shelf is offering, with where it currently sits so a move is never a surprise. */
@Composable
private fun OfferedRow(document: DocumentFacts, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                document.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(whereItSits(document), document.descriptor).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Where a document is filed now, in words.
 *
 * Shown because attaching *moves* it, and a document that is already on the furnace should not
 * silently become the project's. The household's own drawer is named rather than left blank — it is
 * a real place, not a missing value.
 */
private fun whereItSits(document: DocumentFacts): String =
    document.owner.label
        ?: document.owner.appKey?.let { AppId.fromKey(it)?.defaultDisplayName ?: it }
        ?: Shelf.HOUSEHOLD_LABEL
