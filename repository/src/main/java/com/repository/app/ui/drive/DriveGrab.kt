package com.repository.app.ui.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.operations.suite.ui.fields.SuiteTextField
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import com.repository.app.logic.Drive
import com.repository.app.logic.Drives
import com.repository.app.logic.ShelfManifest
import com.repository.app.logic.Sidecar
import com.repository.app.logic.Transfer
import com.repository.app.logic.TransferChoice
import com.repository.app.logic.TransferOutcome
import kotlinx.coroutines.launch

/**
 * Grab these, off that drive, onto the shelf.
 *
 * The request this was built for, in the household's own words: *"get those project docs out of
 * OneDrive and onto the shelf so I can attach them to the project."* Which is four operations that
 * every other route makes you do one file at a time — open the drive's app, download, find the
 * download, file it — repeated until you stop bothering and leave the documents on the drive.
 *
 * So: pick a drive, pick the files, **look at what you picked**, and file the lot in one press.
 *
 * ### Why there is a list in the middle
 *
 * The review step is the whole point and it would be easy to mistake for ceremony. A folder on a
 * drive is organised for the drive — `docs-final-v3-REAL.docx` sitting next to two dead drafts — and
 * the shelf is organised for the household. Between the picker closing and anything being copied
 * there is one screen where you untick the two drafts and call the third one what it is. Afterwards
 * is too late: the shelf has three documents in it and renaming them is three more dialogs.
 *
 * ### What it does not do
 *
 * It does not remember the drive's copy, watch it, or ever look at it again. What lands on the shelf
 * is **a copy taken at a moment**, exactly like a document photographed off a kitchen table, and the
 * app is honest about that rather than implying a link it does not keep. A sync would mean this
 * module holding a credential, polling somebody's Drive, and quietly changing documents a household
 * believes it has filed. See `logic/Drives` for the whole argument.
 */
@Composable
fun DriveGrabDialog(
    owner: DocumentOwner,
    onDismiss: () -> Unit,
    kinds: List<DocumentKind> = Documents.COMMON,
    onFiled: (TransferOutcome) -> Unit = {}
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    var drive by remember { mutableStateOf(shelf.prefs.lastDrive ?: Drive.GOOGLE_DRIVE) }
    var choices by remember { mutableStateOf<List<TransferChoice>>(emptyList()) }
    var kind by remember { mutableStateOf(kinds.firstOrNull() ?: DocumentKind.OTHER) }
    var filing by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }

    /**
     * The shelf that wrote these documents, if the household picked its manifest along with them.
     *
     * It is not offered as one of the files to file — it is not a document — so it is taken out of
     * the review list and kept here instead. Picking it is optional and picking it is worth it: with
     * it each document arrives with the title, kind, note and what-it-is-about the other shelf had,
     * and one already here is skipped rather than filed twice. See `logic/Sidecar`.
     */
    var manifest by remember { mutableStateOf<ShelfManifest?>(null) }

    val picker = rememberLauncherForActivityResult(PickFiles()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // The durable grant first, on every one of them: the transient grant dies with this
        // activity and copying a folder's worth of PDFs over a phone connection outlives it.
        uris.forEach { shelf.drives.holdOnTo(it) }
        scope.launch {
            // Describing is a query into each file's provider, which for a cloud drive can go to the
            // network — so it happens off the main thread and the dialog says "Reading…" meanwhile.
            reading = true
            val picked = shelf.drives.describe(uris)
            reading = false
            // Remember where this actually came from rather than which chip was lit, so the picker
            // opens there next time even if the household walked somewhere else inside it.
            picked.firstOrNull()?.let { first ->
                val actual = first.drive ?: drive
                shelf.prefs.rememberPlace(actual, first.uri)
                shelf.prefs.lastDrive = actual
            }
            // Added to what is already there rather than replacing it, because "Pick more" has to
            // mean more: a household grabbing a project's paperwork out of two folders should not
            // lose the first four to fetch the fifth. A file picked twice is still one row.
            // The manifest is read and set aside rather than reviewed: it is the shelf, not a
            // document, and offering to file it would put a JSON file in somebody's drawer.
            val (manifests, documents) = picked.partition { Sidecar.isManifest(it.displayName) }
            manifests.forEach { entry ->
                shelf.drives.readManifest(Uri.parse(entry.uri))?.let { manifest = it }
            }

            val added = Transfer.plan(documents).map { choice ->
                // The title the other shelf had, in the review list, where somebody can still
                // change it — rather than the one guessed from the file name underneath it.
                manifest?.entryFor(choice.item.displayName)
                    ?.let { entry -> choice.copy(title = entry.title) }
                    ?: choice
            }
            choices = choices + added.filterNot { fresh -> choices.any { it.item.uri == fresh.item.uri } }
        }
    }

    fun launch() {
        shelf.prefs.lastDrive = drive
        val start = shelf.drives.lastPlaceOn(drive, shelf.prefs.lastPlace(drive))
        runCatching { picker.launch(start) }
    }

    AlertDialog(
        onDismissRequest = { if (!filing) onDismiss() },
        title = { Text(if (choices.isEmpty()) "Get documents from a drive" else "File these") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choices.isEmpty()) {
                    Text(
                        "Google Drive, OneDrive and Dropbox each appear in Android's own file " +
                            "picker. Choose where to start and pick as many files as you want — " +
                            "they are copied onto the shelf, and the drive is not touched again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DriveChips(selected = drive, onSelect = { drive = it })
                    Text(
                        "A starting point, not a filter — the picker will let you walk anywhere " +
                            "from there, and the shelf records where each file actually came from.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Said where somebody is about to pick, because the manifest is easy to walk
                    // past — it is one small file among the documents, and picking it is the
                    // difference between the shelf arriving and the bytes arriving.
                    Text(
                        "If the folder was saved from another phone's shelf it also holds " +
                            "“${Sidecar.FILE_NAME}”. Pick that too and each document arrives with " +
                            "its name, its kind and what it is about — and anything already on this " +
                            "shelf is left alone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { launch() },
                        enabled = !reading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (reading) "Reading…" else "Choose files on ${drive.label}")
                    }
                } else {
                    Text(
                        Transfer.headline(choices),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // One kind for the batch. Files picked out of one folder in one go are almost
                    // always the same sort of thing, and asking seven times is how a household ends
                    // up filing everything as "Other".
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (kinds + kind).distinct().forEach { entry ->
                            FilterChip(
                                selected = entry == kind,
                                onClick = { kind = entry },
                                label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(choices, key = { it.item.uri }) { choice ->
                            ChoiceRow(
                                choice = choice,
                                onChange = { updated ->
                                    choices = choices.map { if (it.item.uri == updated.item.uri) updated else it }
                                }
                            )
                        }
                    }

                    manifest?.let {
                        Text(
                            "These came off another phone's shelf, so they keep their names and " +
                                "what they are about.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Transfer.duplicateWarning(choices)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(onClick = { launch() }) { Text("Pick more") }
                }
            }
        },
        confirmButton = {
            val included = Transfer.included(choices)
            TextButton(
                enabled = included.isNotEmpty() && !filing,
                onClick = {
                    filing = true
                    scope.launch {
                        val outcome = shelf.documents.importAll(choices, manifest, kind, owner)
                        filing = false
                        onFiled(outcome)
                        onDismiss()
                    }
                }
            ) {
                Text(
                    when {
                        filing -> "Filing…"
                        included.size <= 1 -> "File it"
                        else -> "File ${included.size}"
                    }
                )
            }
        },
        dismissButton = { TextButton(enabled = !filing, onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The drives, as a starting point. Clouds first, the phone last — that is the order people ask in. */
@Composable
internal fun DriveChips(selected: Drive, onSelect: (Drive) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Drives.OFFERED.forEach { drive ->
            FilterChip(
                selected = drive == selected,
                onClick = { onSelect(drive) },
                label = { Text(drive.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/**
 * One picked file, on its way in: in or out, and what it will be called.
 *
 * The title is editable here and nowhere else in the flow, because here is where the household still
 * has the drive's folder in their head. The file's own name stays visible underneath it — renaming
 * `docs-final-v3.docx` to "Chapter four" is only safe if you can still see which file you did it to.
 */
@Composable
private fun ChoiceRow(choice: TransferChoice, onChange: (TransferChoice) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(
            checked = choice.include,
            onCheckedChange = { onChange(choice.copy(include = it)) }
        )
        Column(Modifier.weight(1f)) {
            SuiteTextField(
                label = "What it is",
                value = choice.title,
                onValueChange = { onChange(choice.copy(title = it)) },
                // Unticking a file greys its name rather than hiding it: the review list is here so
                // somebody can see what they are about to file, and a row that vanishes when it is
                // excluded takes the evidence of the decision with it.
                enabled = choice.include
            )
            Text(
                listOfNotNull(
                    choice.item.displayName,
                    choice.item.origin,
                    Documents.formatSize(choice.item.sizeBytes)
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Android's own file picker, opened at a drive, taking as many files as somebody selects.
 *
 * Written out rather than using `ActivityResultContracts.OpenMultipleDocuments` for one reason: that
 * contract has nowhere to put `EXTRA_INITIAL_URI`, and without it every import starts wherever the
 * picker last happened to be — which for a household filing project docs off OneDrive means walking
 * the same four folders every time. The rest is the stock intent.
 *
 * The result arrives two ways and both are handled: `clipData` when more than one file was chosen,
 * `data` when exactly one was. Missing the second is the classic version of this bug, and it shows
 * up as "picking one file does nothing".
 */
private class PickFiles : ActivityResultContract<Uri?, List<Uri>>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // Any type at all: a household is handed PDFs, photographs, spreadsheets and the
            // occasional .docx, and a picker that only took PDFs is one people file around.
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .apply { input?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(intent.data)
    }
}
