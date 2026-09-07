package com.repository.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import com.repository.app.logic.RepositoryDestination
import com.repository.app.logic.RepositoryLinks
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
 *
 * What this activity does beyond showing that list is take a **destination** off the intent that
 * started it: the shelf already narrowed to one asset's documents, or to the one document somebody
 * was sent for. Being pointed at is what this app is for, and until there was an address to point
 * with, the most anything in the suite could do was start it and leave somebody scrolling.
 */
class MainActivity : ComponentActivity() {

    /**
     * Where to open, from the intent that arrived. Compose state rather than a field read once,
     * because [onNewIntent] delivers the second link into an activity that is already running.
     */
    private var openDestination by mutableStateOf<RepositoryDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RepositoryApp.get(this)
        openDestination = destinationOf(intent)

        setContent {
            RepositoryTheme {
                RepositoryShell(
                    opening = openDestination,
                    onOpened = { openDestination = null }
                )
            }
        }
    }

    /**
     * A second link into an app that is already open — the launch flags below reuse this activity
     * rather than stacking a copy of the shelf on top of itself.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinationOf(intent)?.let { openDestination = it }
    }

    private fun destinationOf(intent: Intent?): RepositoryDestination? =
        RepositoryLinks.parse(intent?.getStringExtra(EXTRA_OPEN_DESTINATION))

    companion object {

        /**
         * Intent extra naming somewhere on the shelf to open at — an address in the vocabulary of
         * `logic/RepositoryLinks`. Absent, or unrecognised, means "open the shelf".
         */
        const val EXTRA_OPEN_DESTINATION = "com.repository.app.extra.OPEN_DESTINATION"

        /**
         * An intent that opens the shelf at [destination] — how anything in the suite links into it.
         *
         * Deliberately an **explicit** intent with no URL scheme and no exported filter beyond the
         * activity itself. This module declares no permissions and holds the household's paperwork;
         * a `repository://` scheme would let any app on the phone address a mortgage statement by
         * URI, which is a surface a filing cabinet has no reason to offer for a suite that shares
         * one process.
         *
         * A destination that cannot be written down (see [RepositoryLinks.format]) simply opens the
         * shelf, because failing to narrow a list is a disappointment and failing to open somebody's
         * documents is a bug.
         */
        fun intentFor(context: Context, destination: RepositoryDestination): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    RepositoryLinks.format(destination)?.let { putExtra(EXTRA_OPEN_DESTINATION, it) }
                }
    }
}

@Composable
private fun RepositoryShell(
    opening: RepositoryDestination? = null,
    onOpened: () -> Unit = {}
) {
    val context = LocalContext.current
    val shelf = remember { RepositoryApp.get(context) }
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }

    /**
     * What the shelf is showing, as an address rather than as the destination itself: it has to
     * survive the process being killed behind a file picker, and an address is a string, which
     * `rememberSaveable` can keep without this module owning a Parcelable.
     */
    var showingAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val showing = RepositoryLinks.parse(showingAddress) ?: RepositoryDestination.Shelf

    // A link is checked against the shelf before anything is narrowed to it, and a stale one lands
    // on the whole list — see `DocumentRepository.resolve`. Somebody sent to a document deleted
    // since should find the shelf, not an empty screen insisting nothing is there.
    LaunchedEffect(opening) {
        val destination = opening ?: return@LaunchedEffect
        showingAddress = shelf.documents.resolve(destination)?.let { RepositoryLinks.format(it) }
        onOpened()
    }

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

    ShelfScreen(
        onFile = { runCatching { picker.launch(arrayOf("*/*")) } },
        showing = showing,
        onShowingChange = { next -> showingAddress = RepositoryLinks.format(next) }
    )

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
