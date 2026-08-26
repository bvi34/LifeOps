package com.operations.sandbox.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.sandbox.BackupCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The file types the restore picker will show; some pickers hand a zip out as octet-stream. */
val RESTORE_MIME_TYPES = arrayOf("application/zip", "application/octet-stream")

/**
 * Everything the backup buttons do, held above the screen that shows them.
 *
 * It lives at the shell level rather than inside the settings screen for one reason: an archive can
 * take a while, and its coroutine dies with whatever composable owns the scope. Hoisting it here
 * means backing out to the home screen mid-backup leaves the work running, and the status message
 * is still there when the settings are opened again.
 *
 * The work itself is [BackupCenter]'s — this is state and wiring only.
 */
@Stable
class BackupController(
    private val context: Context,
    private val center: BackupCenter,
    private val scope: CoroutineScope
) {
    val apps: List<BackupContributor> get() = center.contributors

    var selected by mutableStateOf(center.contributors.map { it.appId }.toSet())
        private set

    var working by mutableStateOf(false)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    fun setSelected(appId: AppId, include: Boolean) {
        if (working) return
        selected = if (include) selected + appId else selected - appId
    }

    /** Stream the selected apps into the document the picker returned. */
    fun backupTo(uri: Uri?) {
        if (uri == null) {
            status = "Backup cancelled"
            return
        }
        val chosen = selected
        scope.launch {
            working = true
            status = "Backing up ${chosen.size} app(s)…"
            val result = runCatching {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open the destination file")
                center.backup(chosen, out)
            }
            working = false
            status = result.fold(
                onSuccess = { "Backup saved — ${chosen.joinToString { it.defaultDisplayName }}." },
                onFailure = { "Backup failed: ${it.message}" }
            )
        }
    }

    /**
     * Read the archive's manifest first, then restore only the apps that are both ticked here and
     * actually present in it — so restoring a two-app archive with everything ticked is not an error.
     */
    fun restoreFrom(uri: Uri?) {
        if (uri == null) {
            status = "Restore cancelled"
            return
        }
        val chosen = selected
        scope.launch {
            working = true
            status = "Reading archive…"
            val result = runCatching {
                val manifest = context.contentResolver.openInputStream(uri)?.use { center.peek(it) }
                    ?: error("This file isn't a readable Operations Sandbox archive")
                val present = manifest.apps.mapNotNull { AppId.fromKey(it.appId) }.toSet()
                val toRestore = chosen intersect present
                if (toRestore.isEmpty()) error("None of the selected apps are in this archive")
                context.contentResolver.openInputStream(uri)!!.use { center.restore(toRestore, it) }
                toRestore
            }
            working = false
            status = result.fold(
                onSuccess = { done ->
                    "Restored ${done.joinToString { it.defaultDisplayName }}. " +
                        "Fully close Operations Sandbox (swipe it from Recents) and reopen it so the " +
                        "restored data loads."
                },
                onFailure = { "Restore failed: ${it.message}" }
            )
        }
    }
}

@Composable
fun rememberBackupController(center: BackupCenter): BackupController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(center, scope) { BackupController(context, center, scope) }
}

fun defaultBackupName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "operations-backup-$stamp.zip"
}
