package com.repository.app.ui.drive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.repository.app.RepositoryApp
import com.repository.app.logic.Drive
import com.repository.app.logic.Drives
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.Transfer
import kotlinx.coroutines.launch

/**
 * Put these back on a drive — the same targeting, pointing the other way.
 *
 * Send a copy already exists and is the right tool for one document going to one person: it opens
 * the share sheet, and a drive's own app is on it. What the share sheet cannot do is *this folder,
 * these six documents, again next month* — every send is a fresh chooser and a fresh walk through
 * somebody's folder tree.
 *
 * So the folder is chosen **once** and remembered per drive. "OneDrive · Project docs" is a place
 * the household named, not one this app guessed at, and the grant behind it is one they gave to that
 * folder rather than to a drive. Changing it is one press; there is no state here beyond a URI.
 *
 * ### It writes, and never replaces
 *
 * Every document lands under its own title as a new file. If one of that name is already there, the
 * drive's provider makes "Statement (1).pdf" — and that behaviour is left alone deliberately. A
 * household's drive is their record; an export that silently overwrote a file on it would be this
 * app destroying data it does not own to save somebody a rename.
 */
@Composable
fun DriveSaveDialog(
    documents: List<DocumentFacts>,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    var drive by remember { mutableStateOf(shelf.prefs.lastDrive ?: Drive.GOOGLE_DRIVE) }
    var folder by remember { mutableStateOf(shelf.prefs.lastFolder(drive)) }
    var folderLabel by remember { mutableStateOf(shelf.prefs.lastFolderLabel(drive)) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        // A folder we cannot keep is one to ask for again next time rather than remember and then
        // fail at, so the grant decides whether it is worth writing down.
        val kept = shelf.drives.keepFolder(tree)
        val label = shelf.drives.folderLabel(tree)
        folder = tree.toString()
        folderLabel = label
        problem = null
        val actual = Drives.of(tree.toString()) ?: drive
        drive = actual
        shelf.prefs.lastDrive = actual
        if (kept) shelf.prefs.rememberFolder(actual, tree.toString(), label)
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(if (documents.size == 1) "Save to a drive" else "Save ${documents.size} to a drive")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (documents.size == 1) {
                        "A copy of “${documents.first().title}”, written into a folder you choose. " +
                            "The document stays on the shelf."
                    } else {
                        "A copy of each, written into one folder you choose. They stay on the shelf."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DriveChips(
                    selected = drive,
                    onSelect = { chosen ->
                        drive = chosen
                        folder = shelf.prefs.lastFolder(chosen)
                        folderLabel = shelf.prefs.lastFolderLabel(chosen)
                    }
                )

                Text(
                    folderLabel?.let { "Saving into $it" }
                        ?: "No folder chosen on ${drive.label} yet",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedButton(
                    onClick = {
                        val start = shelf.drives.lastPlaceOn(drive, shelf.prefs.lastPlace(drive))
                        runCatching { chooser.launch(start) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (folder == null) "Choose a folder" else "Choose a different folder")
                }

                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = folder != null && documents.isNotEmpty() && !saving,
                onClick = {
                    val target = folder ?: return@TextButton
                    saving = true
                    scope.launch {
                        val tree = target.toUri()
                        if (!shelf.drives.canStillWriteTo(tree)) {
                            // The grant has lapsed — the folder was moved, the drive's app was
                            // reinstalled, or the household revoked it. Say so and forget it, rather
                            // than reporting a save that did not happen.
                            shelf.prefs.forgetFolder(drive)
                            folder = null
                            folderLabel = null
                            saving = false
                            problem = "That folder is no longer ours to write into. Choose it again."
                            return@launch
                        }
                        var saved = 0
                        documents.forEach { document ->
                            val copy = shelf.documents.exportCopy(document)
                            val written = copy?.let {
                                shelf.drives.saveInto(tree, it, document.title, document.mimeType)
                            }
                            if (written != null) saved++
                        }
                        saving = false
                        if (saved == 0) {
                            problem = "Nothing could be written there."
                            return@launch
                        }
                        onSaved(Transfer.savedLine(saved, drive, folderLabel))
                        onDismiss()
                    }
                }
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}
