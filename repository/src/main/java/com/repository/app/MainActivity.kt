package com.repository.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import com.repository.app.ui.attach.FileDialog
import com.repository.app.ui.shelf.ShelfScreen
import com.repository.app.ui.theme.RepositoryTheme
import kotlinx.coroutines.launch

/**
 * Repository's entry point: one screen, because the app answers one question — *where is that
 * document?*
 *
 * There is no navigation graph and no tabs. A shelf that needed a menu would be a filing cabinet,
 * and the whole argument for this app is that a household should not have to have filed things
 * correctly to find them.
 *
 * Filing straight onto the shelf, from here, is for the documents that belong to no app at all: a
 * will, a passport, the survey, the marriage certificate. Everything else is filed from the app that
 * owns the thing — and lands in the same list.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RepositoryApp.get(this)

        setContent {
            RepositoryTheme {
                RepositoryShell()
            }
        }
    }
}

@Composable
private fun RepositoryShell() {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The durable grant, taken before the copy: the transient one expires with this activity and
        // a large file can outlive it.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        title = Documents.titleFrom(shelf.files.describe(uri).displayName)
        picked = uri.toString()
    }

    ShelfScreen(onFile = { runCatching { picker.launch(arrayOf("*/*")) } })

    picked?.let { uri ->
        FileDialog(
            title = title,
            kind = DocumentKind.OTHER,
            kinds = Documents.COMMON,
            heading = "File this document",
            onDismiss = { picked = null },
            onSave = { name, kind, note ->
                picked = null
                scope.launch {
                    shelf.documents.file(
                        source = android.net.Uri.parse(uri),
                        title = name,
                        kind = kind,
                        // Filed here means filed against nothing — the household's own drawer.
                        owner = DocumentOwner.HOUSEHOLD,
                        note = note
                    )
                }
            }
        )
    }
}
