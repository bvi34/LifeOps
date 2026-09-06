package com.project.app.ui.docs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.BlockType
import com.project.app.logic.DocBlock
import com.project.app.logic.DocBlocks
import com.project.app.logic.DocRevision
import com.project.app.logic.ProjectPulse
import com.project.app.logic.Revisions
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.formatDayTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DocHistoryViewModel(
    private val repo: ProjectRepository,
    private val docId: String
) : ViewModel() {

    val revisions: StateFlow<List<DocRevision>> =
        repo.observeRevisions(docId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The versions whose text has been read in, kept by id.
     *
     * A version's blocks are loaded when somebody opens it rather than with the list, because the
     * list is the cheap question ("what have I got") and the text is the expensive one. They are
     * kept once loaded: a version cannot change, so re-reading it on every expand would be a query
     * that can only ever return the same answer.
     */
    private val _opened = MutableStateFlow<Map<String, List<DocBlock>>>(emptyMap())
    val opened: StateFlow<Map<String, List<DocBlock>>> = _opened.asStateFlow()

    fun open(revisionId: String) {
        if (_opened.value.containsKey(revisionId)) return
        viewModelScope.launch {
            val blocks = repo.revisionBlocks(revisionId)
            _opened.update { it + (revisionId to blocks) }
        }
    }

    fun restore(revisionId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.restoreRevision(docId, revisionId)) }
    }

    class Factory(
        private val repo: ProjectRepository,
        private val docId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DocHistoryViewModel(repo, docId) as T
    }
}

/**
 * What this document used to say, and the way back to it.
 *
 * A version is kept before every edit that can throw the whole document away — pasting Markdown
 * over it, rebuilding its tables, or restoring an earlier version. So this list is short and every
 * row on it is a moment somebody was about to do something drastic, which is exactly the list worth
 * having: the question asked here is never "what did I write on Tuesday", it is "give me back what
 * was there before I did that".
 *
 * Each row says **why** it was kept rather than only when, because a column of timestamps is not a
 * thing anybody can choose from. Opening one shows the text as it stood; restoring keeps the
 * current text first, so coming back is itself undoable and the history is not a one-way door.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocHistoryScreen(
    vm: DocHistoryViewModel,
    onBack: () -> Unit
) {
    val revisions by vm.revisions.collectAsStateWithLifecycle()
    val opened by vm.opened.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<DocRevision?>(null) }

    LaunchedEffect(expanded) { expanded?.let { vm.open(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("Version history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (revisions.isEmpty()) {
            EmptyState(
                title = "No versions yet",
                detail = "A version is kept automatically before anything replaces this document " +
                    "wholesale — pasting Markdown in, or rebuilding its tables. You can also keep " +
                    "one by hand from the document's menu before trying something.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(revisions, key = { it.id }) { revision ->
                RevisionCard(
                    revision = revision,
                    blocks = opened[revision.id],
                    isExpanded = expanded == revision.id,
                    onToggle = { expanded = if (expanded == revision.id) null else revision.id },
                    onRestore = { confirming = revision }
                )
            }

            item(key = "cap") {
                // Said plainly rather than left to be discovered when a version somebody wanted has
                // quietly gone.
                Text(
                    "The most recent ${Revisions.KEEP} versions of a document are kept.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                )
            }
        }
    }

    confirming?.let { revision ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "The document goes back to how it was on ${formatDayTime(revision.savedAt)} " +
                        "— ${ProjectPulse.count(revision.wordCount)} " +
                        "${ProjectPulse.plural(revision.wordCount, "word")}. What it says right now " +
                        "is kept as a version first, so you can come straight back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val going = revision
                    confirming = null
                    vm.restore(going.id) { done ->
                        // Back to the document, which is where the restored text is. The failure
                        // case is all but unreachable from this screen — the version would have to
                        // have gone between drawing the row and pressing the button — but silently
                        // doing nothing is the one response that leaves somebody unsure whether
                        // their writing came back.
                        if (done) onBack() else scope.launch {
                            snackbars.showSnackbar("That version is no longer there")
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RevisionCard(
    revision: DocRevision,
    blocks: List<DocBlock>?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(revision.reason.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatDayTime(revision.savedAt)} · " +
                            "${ProjectPulse.count(revision.wordCount)} " +
                            ProjectPulse.plural(revision.wordCount, "word"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Hide this version" else "Read this version"
                )
            }

            if (!isExpanded) return@Column

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            when {
                blocks == null -> Text(
                    "Reading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                blocks.isEmpty() -> Text(
                    "This version is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ordinals = remember(blocks) { DocBlocks.ordinals(blocks) }
                    blocks.forEachIndexed { index, block ->
                        RevisionBlock(block, ordinals[index])
                    }
                }
            }

            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.padding(top = 12.dp)
            ) { Text("Restore this version") }
        }
    }
}

/**
 * A block of a kept version, drawn and nothing else.
 *
 * Deliberately not the reader's `ReadOnlyBlock`: there, a to-do can be ticked and a table turned
 * round, because that is a document being used. This is a *record* of what a document said, and
 * every control on it would be a control that either does nothing or quietly edits the past.
 */
@Composable
private fun RevisionBlock(block: DocBlock, ordinal: Int) {
    when (block.type) {
        BlockType.DIVIDER -> HorizontalDivider(Modifier.padding(vertical = 4.dp))

        BlockType.CODE -> CodeSurface(block.text)

        BlockType.QUOTE -> QuoteSurface(block.text)

        BlockType.TABLE -> MarkdownTableView(source = block.text, cards = false)

        BlockType.TODO -> Row(verticalAlignment = Alignment.Top) {
            Text(
                text = if (block.checked) "☑" else "☐",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (block.checked) TextDecoration.LineThrough else null
                ),
                color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        BlockType.BULLET, BlockType.NUMBERED -> Row(verticalAlignment = Alignment.Top) {
            Text(
                text = if (block.type == BlockType.NUMBERED) "$ordinal." else "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            MarkdownText(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }

        else -> MarkdownText(
            text = block.text,
            style = when (block.type) {
                BlockType.HEADING1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                BlockType.HEADING2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                BlockType.HEADING3 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                else -> MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
