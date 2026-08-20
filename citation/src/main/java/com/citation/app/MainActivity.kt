package com.citation.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.ui.ReaderScreen
import com.citation.app.ui.ReaderViewModel
import com.citation.app.ui.theme.CitationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's single entry point. It awaits the async-built repository, then hosts the
 * library/reader Compose tree. One activity is enough — Citation is a focused reading surface.
 *
 * It also ingests a book opened from another app: an `ACTION_VIEW` on an EPUB or a PDF is read and
 * handed to the same import path the New tab's pickers use, so the file lands in the library and
 * opens in the reader rather than dropping you on the home shell with nothing imported.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CitationApplication.get(this)

        // A book file opened from a file manager, a browser download, or another app's "open with".
        val openedFile: Uri? = intent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data

        setContent {
            CitationTheme {
                var vm by remember { mutableStateOf<ReaderViewModel?>(null) }
                // Saveable so a rotation doesn't ingest the same file a second time.
                var ingested by rememberSaveable { mutableStateOf(false) }
                // Build the ViewModel once the repository is ready.
                LaunchedEffect(Unit) {
                    val repo = app.repository.await()
                    vm = ViewModelProvider(
                        this@MainActivity,
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ReaderViewModel(repo) as T
                        }
                    )[ReaderViewModel::class.java]
                }
                val ready = vm
                LaunchedEffect(ready, openedFile) {
                    if (ready == null || openedFile == null || ingested) return@LaunchedEffect
                    // Read first, mark second: a rotation *during* the read cancels this effect, and
                    // leaving the flag unset lets the recreated activity start the import over rather
                    // than swallow it. Everything after the read is dispatched to the ViewModel's own
                    // scope, which a rotation doesn't touch.
                    val bytes = readFully(openedFile)
                    val title = displayTitle(openedFile, fallback = "PDF")
                    ingested = true
                    ingest(ready, bytes, title)
                }
                ready?.let { readerVm ->
                    ReaderScreen(readerVm)
                    // An import that arrived from outside has no picker screen to report back to, so
                    // its failures are raised over whatever the reader is showing.
                    val alert by readerVm.importAlert.collectAsStateWithLifecycle()
                    alert?.let { message ->
                        AlertDialog(
                            onDismissRequest = { readerVm.dismissImportAlert() },
                            title = { Text("Couldn't import that file") },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = { readerVm.dismissImportAlert() }) { Text("OK") }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads the opened file and routes it to the matching importer. The format is decided by the
     * file's own first bytes rather than the intent's MIME type: senders type these files
     * inconsistently (an EPUB arrives as `application/epub`, `application/epub+zip`, or
     * `application/octet-stream`), while the magic number is never wrong — a PDF starts `%PDF`, an
     * EPUB is a zip (`PK`).
     */
    private fun ingest(vm: ReaderViewModel, bytes: ByteArray?, title: String) {
        if (bytes == null || bytes.isEmpty()) {
            vm.reportImportProblem("That file couldn't be opened.")
            return
        }
        when {
            bytes.startsWith("%PDF") -> vm.importPdf(bytes, title)
            bytes.startsWith("PK") -> vm.importEpub(bytes, openAfter = true)
            else -> vm.reportImportProblem("That file isn't an EPUB or a PDF.")
        }
    }

    /** The opened file's whole content, or null if it couldn't be read. */
    private suspend fun readFully(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    /**
     * The file's user-visible name, extension trimmed — the title an imported PDF is filed under.
     * Off the main thread with the read: it's a content-provider query, however small.
     */
    private suspend fun displayTitle(uri: Uri, fallback: String): String = withContext(Dispatchers.IO) {
        val queried = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
        val name = queried ?: uri.lastPathSegment?.substringAfterLast('/')
        name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }
}

/** True when this file's leading bytes are exactly [magic] — a format sniff, ASCII magic numbers only. */
private fun ByteArray.startsWith(magic: String): Boolean =
    size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }
