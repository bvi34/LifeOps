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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.operations.backupkit.AppId
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.Shelf
import com.repository.app.ui.attach.DocumentRow
import com.repository.app.ui.attach.openDocument
import com.repository.app.ui.attach.sendDocument
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
 */
@Composable
fun ShelfScreen(onFile: () -> Unit) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    val documents by remember { shelf.documents.observeShelf() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var drawer by remember { mutableStateOf<String?>(null) }

    val drawers = remember(documents) { Shelf.drawers(documents) { key -> AppId.fromKey(key)?.defaultDisplayName } }
    val visible = remember(documents, query, drawer) {
        val inDrawer = when (drawer) {
            null -> documents
            Shelf.HOUSEHOLD_LABEL -> documents.filter { it.owner.appKey == null }
            else -> documents.filter { it.owner.appKey == drawer }
        }
        Shelf.search(inDrawer, query)
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
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item(key = "headline") {
                Text(
                    Shelf.headline(visible),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                            selected = drawer == null,
                            onClick = { drawer = null },
                            label = { Text("Everything") }
                        )
                        drawers.forEach { group ->
                            val key = group.appKey ?: Shelf.HOUSEHOLD_LABEL
                            FilterChip(
                                selected = drawer == key,
                                onClick = { drawer = if (drawer == key) null else key },
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
                                    "another app land here too."
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
                    onSend = { scope.launch { sendDocument(context, document) } }
                )
            }

            item(key = "tail") { Spacer(Modifier.height(72.dp)) }
        }
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
private fun ShelfRow(document: DocumentFacts, onOpen: () -> Unit, onSend: () -> Unit) {
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
