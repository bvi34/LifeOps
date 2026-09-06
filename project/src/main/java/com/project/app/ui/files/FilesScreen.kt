package com.project.app.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.operations.backupkit.AppId
import com.project.app.data.repository.AttachTarget
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.AttachKind
import com.repository.app.logic.DocumentKind
import com.repository.app.ui.attach.DocumentsPanel
import kotlinx.coroutines.launch

class FilesViewModel(
    private val repo: ProjectRepository,
    private val projectId: String,
    private val kind: AttachKind,
    private val recordId: String
) : ViewModel() {

    /** Null until read; still null afterwards means the record has gone. */
    var target by mutableStateOf<AttachTarget?>(null)
        private set

    var missing by mutableStateOf(false)
        private set

    fun load() {
        viewModelScope.launch {
            val found = repo.attachTarget(projectId, kind, recordId)
            target = found
            missing = found == null
        }
    }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String,
        private val kind: AttachKind,
        private val recordId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FilesViewModel(repo, projectId, kind, recordId) as T
    }
}

/**
 * The files on one thing in a project — a scene, a lore entry, a card, or the project itself.
 *
 * A screen rather than a section inside the record's dialog, and that is the point of it. Filing a
 * document is three affordances wide (from this phone, off a drive, or something already on the
 * shelf) and the result is a list; an `AlertDialog` with all of that inside it is a dialog you
 * cannot read. One screen, reached from every record that can hold files, is also one place to
 * change when the panel grows.
 *
 * The documents themselves are **Repository's**, shown here in place. Project stores no bytes and
 * keeps no second document table: a brief that arrived as a PDF is a document the household filed,
 * and the household's shelf is where it belongs — findable later by somebody who has forgotten
 * which app it came through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: FilesViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.load() }

    val target = vm.target
    val missing = vm.missing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(target?.name ?: "Files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            when {
                // A link to a record's files can outlive the record — from the outline you left
                // open in another tab of your head, or a card somebody deleted. Saying so beats
                // an empty drawer that looks like it lost your paperwork.
                missing -> Text(
                    "That ${target?.kind?.noun ?: "record"} is no longer here, so there is nothing " +
                        "to file on it. Anything that was attached is still on the suite's shelf.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                target == null -> Text(
                    "Reading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    Text(
                        target.shelfLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DocumentsPanel(
                        appKey = AppId.PROJECT.key,
                        recordKey = target.recordKey,
                        // Project-led, because this drawer sits on a shelf beside a mortgage
                        // statement and a boiler manual, where "The docks" is a question.
                        recordLabel = target.shelfLabel,
                        modifier = Modifier.padding(top = 8.dp),
                        kinds = PROJECT_FILE_KINDS,
                        emptyLine = emptyLineFor(target.kind)
                    )
                }
            }
        }
    }
}

private fun emptyLineFor(kind: AttachKind): String = when (kind) {
    AttachKind.PROJECT ->
        "The brief, the contract, the reference PDFs — add them from this phone, get them off " +
            "Google Drive or OneDrive, or attach something already on the shelf."
    AttachKind.OUTLINE ->
        "Reference for this piece — the photograph of the street, the article it is based on, " +
            "the notes somebody sent you about it."
    AttachKind.LORE ->
        "Reference for this entry — a map, a portrait, the source you are keeping it honest against."
    AttachKind.CARD ->
        "Paperwork for this piece of work — the signed contract, the brief, the thing you were sent."
}

/**
 * What a project is usually filed with.
 *
 * The same list the project's own Files card has always offered, moved here now that four kinds of
 * record share it — a manual or a warranty belongs to a boiler, not to a manuscript.
 */
private val PROJECT_FILE_KINDS = listOf(
    DocumentKind.CONTRACT,
    DocumentKind.CORRESPONDENCE,
    DocumentKind.REPORT,
    DocumentKind.RECEIPT,
    DocumentKind.RECORD,
    DocumentKind.OTHER
)
