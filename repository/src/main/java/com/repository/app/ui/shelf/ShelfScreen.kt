package com.repository.app.ui.shelf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.backupkit.AppId
import com.operations.suite.ui.fields.SuiteTextField
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.RepositoryDestination
import com.repository.app.logic.Shelf
import com.repository.app.logic.describe
import com.repository.app.logic.Transfer
import com.repository.app.ui.attach.DocumentRow
import com.repository.app.ui.attach.openDocument
import com.repository.app.ui.attach.sendDocument
import com.repository.app.ui.drive.DriveGrabDialog
import com.repository.app.ui.drive.DriveSaveDialog
import kotlinx.coroutines.launch

/**
 * The shelf: everything the household has filed, wherever it came in through.
 *
 * The app exists for one moment — you want the mortgage statement and you do not want to think
 * about which app it lives in. So there is one list, one search box, and no folders to have put
 * things in correctly. A document filed against an asset in Maintenance and one photographed
 * straight onto the shelf sit next to each other, and a lab result Health is only lending sits there
 * too.
 *
 * Search is over the title, the note, the kind and **what the document is about** — so "wrangler"
 * finds the truck's manual, and this module still does not know what a truck is (the owning app said
 * so when it filed it; see `logic/DocumentOwner`).
 *
 * [showing] is how something *else* points at this screen — an asset's documents section saying "and
 * here is the rest of the shelf", Advisor taking somebody to the statement it just quoted. It is a
 * filter on the one list rather than a place the app navigates to, which is why a deep link and a
 * drawer chip are the same piece of state: whatever somebody was sent for, everything else is one
 * press away. See `logic/RepositoryDestination`.
 */
@Composable
fun ShelfScreen(
    onFile: () -> Unit,
    showing: RepositoryDestination = RepositoryDestination.Shelf,
    onShowingChange: (RepositoryDestination) -> Unit = {}
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    val documents by remember { shelf.documents.observeShelf() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var grabbing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<List<DocumentFacts>>(emptyList()) }
    var said by remember { mutableStateOf<String?>(null) }

    val drawers = remember(documents) { Shelf.drawers(documents) { key -> AppId.fromKey(key)?.defaultDisplayName } }
    val visible = remember(documents, query, showing) {
        val scoped = when (showing) {
            is RepositoryDestination.Shelf -> documents
            is RepositoryDestination.Drawer -> documents.filter { it.owner.appKey == showing.appKey }
            is RepositoryDestination.Record -> Shelf.on(documents, showing.appKey, showing.recordKey)
            // The pair, not the id: an id is only unique inside the app that minted it.
            is RepositoryDestination.Document -> documents.filter {
                it.id == showing.documentId && it.sourceKey == showing.sourceKey
            }
        }
        Shelf.search(scoped, query)
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onFile,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("File a document") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "search") {
                SuiteTextField(
                    label = "Search",
                    value = query,
                    onValueChange = { query = it },
                    // A search box is the one field in the suite that should not shift a keyboard
                    // into caps: what goes in it is half a word off a document, not a sentence.
                    capitalise = KeyboardCapitalization.None
                )
            }

            item(key = "headline") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Shelf.headline(visible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The other way documents arrive. The button sits here rather than behind the
                    // FAB because "file this file I am holding" and "go and get those six off
                    // OneDrive" are different errands, and one of them is a whole afternoon of the
                    // other done one at a time.
                    TextButton(onClick = { said = null; grabbing = true }) {
                        Text("Get from a drive…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            said?.let { line ->
                item(key = "said") {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // What somebody was sent here for, when they were sent for something narrower than a
            // drawer — one asset's documents, or one document. The chips below cannot say it (they
            // are per app, and a chip per record would be the folders this app refuses to have), so
            // it is a line, and the line's whole job is to make leaving it obvious. A household that
            // followed a link and then cannot find the rest of its paperwork has been handed a
            // folder after all.
            showing.describe(documents)?.let { line ->
                item(key = "narrowed") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(onClick = { onShowingChange(RepositoryDestination.Shelf) }) {
                            Text("Show everything", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // One chip per drawer that has something in it. They are a filter rather than a
            // navigation: the list is the app, and a drawer you have to open to see into is a folder
            // by another name.
            if (drawers.size > 1) {
                item(key = "drawers") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = showing is RepositoryDestination.Shelf,
                            onClick = { onShowingChange(RepositoryDestination.Shelf) },
                            label = { Text("Everything") }
                        )
                        drawers.forEach { group ->
                            val drawer = RepositoryDestination.Drawer(group.appKey)
                            FilterChip(
                                selected = showing == drawer,
                                onClick = {
                                    onShowingChange(
                                        if (showing == drawer) RepositoryDestination.Shelf else drawer
                                    )
                                },
                                label = { Text(group.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(vertical = 24.dp)) {
                        Text(
                            if (documents.isEmpty()) "Nothing filed yet" else "Nothing matches that",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (documents.isEmpty()) {
                                "The mortgage statement, the title, the warranty — anything you would " +
                                    "otherwise go looking through a drawer for. Documents filed from " +
                                    "another app land here too, and a folder's worth can come " +
                                    "straight off Google Drive or OneDrive."
                            } else {
                                "Search covers what a document is called, what it is about and any note on it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(visible, key = { it.sourceKey.orEmpty() + it.id }) { document ->
                ShelfRow(
                    document = document,
                    onOpen = { scope.launch { openDocument(context, document) } },
                    onSend = { scope.launch { sendDocument(context, document) } },
                    onSaveToDrive = { saving = listOf(document) }
                )
            }

            // Saving what you are *looking at* — a search or a drawer — is the bulk export that a
            // household actually asks for: "put the project's documents on OneDrive", which is a
            // search for the project and one press. Offered only when the list has been narrowed,
            // because "save all 240" is not an errand anybody has.
            if (visible.size > 1 && (query.isNotBlank() || showing !is RepositoryDestination.Shelf)) {
                item(key = "save-these") {
                    TextButton(onClick = { saving = visible }) {
                        Text("Save these ${visible.size} to a drive…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            item(key = "tail") { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (grabbing) {
        DriveGrabDialog(
            // Filed here means filed against nothing — the household's own drawer. What was grabbed
            // is attached to a project or an asset afterwards, from that app's own documents section.
            owner = DocumentOwner.HOUSEHOLD,
            onDismiss = { grabbing = false },
            onFiled = { outcome -> said = Transfer.outcomeLine(outcome) }
        )
    }

    if (saving.isNotEmpty()) {
        DriveSaveDialog(
            documents = saving,
            onDismiss = { saving = emptyList() },
            onSaved = { said = it }
        )
    }
}

/**
 * One document on the shelf, with what it is about under it.
 *
 * A foreign document — one another app is only lending — offers Open and Send and nothing else. Its
 * app has rules about renaming and deleting (Health takes a person's documents with the person), and
 * a second writer would either duplicate those rules or break them.
 */
@Composable
private fun ShelfRow(
    document: DocumentFacts,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onSaveToDrive: () -> Unit
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            DocumentRow(
                document = document,
                onOpen = onOpen,
                onSend = onSend,
                onSaveToDrive = onSaveToDrive,
                onRename = if (document.isForeign) null else ({ editing = true }),
                onDelete = if (document.isForeign) null else ({ deleting = true }),
                subtitle = document.owner.label
                    ?: document.sourceKey?.let { AppId.fromKey(it)?.defaultDisplayName }
            )
        }
    }

    if (editing) {
        com.repository.app.ui.attach.FileDialog(
            title = document.title,
            kind = document.kind,
            kinds = com.repository.app.logic.Documents.COMMON,
            note = document.note,
            heading = "Rename",
            onDismiss = { editing = false },
            onSave = { title, kind, note ->
                editing = false
                scope.launch { shelf.documents.update(document.id, title, kind, note) }
            }
        )
    }

    if (deleting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${document.title}?") },
            text = { Text("The file goes with it. If this is the only copy, send it somewhere first.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleting = false
                    scope.launch { shelf.documents.delete(document.id) }
                }) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleting = false }) { Text("Cancel") }
            }
        )
    }
}
