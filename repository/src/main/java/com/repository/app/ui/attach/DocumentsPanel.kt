package com.repository.app.ui.attach

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.FileProvider
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import kotlinx.coroutines.launch

/**
 * The documents on one thing — the section Repository lends to the app that owns it.
 *
 * This is the other half of the app's reason to exist. A household should be able to find the
 * furnace's manual from the furnace, *and* find it from the shelf without remembering it was filed
 * under Maintenance. Both are the same rows; this is the door that opens from inside the owning app.
 *
 * The whole surface is one call. An app supplies who it is, which of its records this is, and what
 * that record is called — no view model, no plumbing, no store of its own:
 *
 * ```
 * DocumentsPanel(
 *     appKey = AppId.MAINTENANCE.key,
 *     recordKey = asset.id,
 *     recordLabel = asset.name
 * )
 * ```
 *
 * Being one call is not tidiness. It is what makes documents cheap enough to add to the *next* app
 * that needs them, which is how the suite ends up with one shelf instead of five.
 */
@Composable
fun DocumentsPanel(
    appKey: String,
    recordKey: String,
    recordLabel: String,
    modifier: Modifier = Modifier,
    /** What this app usually files, offered first in the picker. */
    kinds: List<DocumentKind> = Documents.COMMON,
    emptyLine: String = "Manuals, warranties, receipts — whatever came with it."
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    val documents by remember(appKey, recordKey) { shelf.documents.observeOn(appKey, recordKey) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var pending by remember { mutableStateOf<PendingFile?>(null) }
    var editing by remember { mutableStateOf<DocumentFacts?>(null) }
    var deleting by remember { mutableStateOf<DocumentFacts?>(null) }
    var failed by remember { mutableStateOf(false) }

    // Any file at all: a household is handed PDFs, photographs, spreadsheets and the occasional
    // .docx, and an app that only accepted PDFs would be an app people keep documents outside of.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Take the durable grant before anything else: the transient one expires with this
        // activity, and copying a large file can outlive it.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val described = shelf.files.describe(uri)
        pending = PendingFile(uri.toString(), Documents.titleFrom(described.displayName))
    }

    // The label travels with the row, so a renamed asset renames its documents on the shelf. Cheap,
    // idempotent, and the alternative is Repository knowing what an asset is.
    LaunchedEffect(appKey, recordKey, recordLabel) {
        shelf.documents.relabel(appKey, recordKey, recordLabel)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (documents.isEmpty()) {
            Text(
                emptyLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            documents.forEach { document ->
                DocumentRow(
                    document = document,
                    onOpen = { scope.launch { openDocument(context, document) } },
                    onSend = { scope.launch { sendDocument(context, document) } },
                    onRename = { editing = document },
                    onDelete = { deleting = document }
                )
            }
        }

        OutlinedButton(onClick = { runCatching { picker.launch(arrayOf("*/*")) } }) {
            Text("Add a document")
        }

        if (failed) {
            Text(
                "That file couldn't be read. Nothing was filed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    pending?.let { file ->
        FileDialog(
            title = file.title,
            kind = kinds.firstOrNull() ?: DocumentKind.OTHER,
            kinds = kinds,
            heading = "File this document",
            onDismiss = { pending = null },
            onSave = { title, kind, note ->
                pending = null
                scope.launch {
                    val id = shelf.documents.file(
                        source = Uri.parse(file.uri),
                        title = title,
                        kind = kind,
                        owner = DocumentOwner(appKey, recordKey, recordLabel),
                        note = note
                    )
                    failed = id == null
                }
            }
        )
    }

    editing?.let { document ->
        FileDialog(
            title = document.title,
            kind = document.kind,
            kinds = kinds,
            note = document.note,
            heading = "Rename",
            onDismiss = { editing = null },
            onSave = { title, kind, note ->
                editing = null
                scope.launch { shelf.documents.update(document.id, title, kind, note) }
            }
        )
    }

    deleting?.let { document ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${document.title}?") },
            text = { Text("The file goes with it. If this is the only copy, send it somewhere first.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = document
                    deleting = null
                    scope.launch { shelf.documents.delete(target.id) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** One document: what it is, and the four things you can do with it. */
@Composable
internal fun DocumentRow(
    document: DocumentFacts,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    subtitle: String? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                document.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(subtitle, document.descriptor).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            document.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onOpen) { Text("Open") }
        TextButton(onClick = { menuOpen = true }) { Text("…") }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Send a copy") }, onClick = { menuOpen = false; onSend() })
            // A document another app is only lending cannot be renamed or deleted from here; the app
            // that owns it has rules about both. See `source/DocumentSource`.
            onRename?.let { rename ->
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; rename() })
            }
            onDelete?.let { delete ->
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; delete() })
            }
        }
    }
}

/** Name it, say what it is, and add anything worth remembering. Three fields, all forgiving. */
@Composable
internal fun FileDialog(
    title: String,
    kind: DocumentKind,
    kinds: List<DocumentKind>,
    heading: String,
    note: String? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, kind: DocumentKind, note: String?) -> Unit
) {
    var name by remember { mutableStateOf(title) }
    var chosen by remember { mutableStateOf(kind) }
    var notes by remember { mutableStateOf(note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // The chips scroll sideways: the kinds do not fit across a phone, and a wrapped row
                // changes height as they change, which makes a dialog jump while you are reading it.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (kinds + chosen).distinct().forEach { entry ->
                        FilterChip(
                            selected = entry == chosen,
                            onClick = { chosen = entry },
                            label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, chosen, notes.takeIf { it.isNotBlank() }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The picked file, held between the picker closing and the form being filled in. */
private data class PendingFile(val uri: String, val title: String)

/**
 * Hand the document to whatever opens that kind of file.
 *
 * A copy is made first (see `data/store/DocumentFiles.exportCopy`) so the viewer gets a URI into the
 * export directory rather than into the store. Nothing happens if there is no viewer installed —
 * which is the honest outcome for a `.dwg` on a phone, and better than a crash.
 */
internal suspend fun openDocument(context: Context, document: DocumentFacts) {
    val shelf = RepositoryApp.get(context)
    val copy = shelf.documents.exportCopy(document) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.repository.fileprovider", copy)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, document.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** The same copy, offered to a share sheet — email it to the buyer, print it, put it in a drive. */
internal suspend fun sendDocument(context: Context, document: DocumentFacts) {
    val shelf = RepositoryApp.get(context)
    val copy = shelf.documents.exportCopy(document) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.repository.fileprovider", copy)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(document.mimeType ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, document.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Send ${document.title}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
