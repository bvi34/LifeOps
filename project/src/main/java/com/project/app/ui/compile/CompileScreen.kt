package com.project.app.ui.compile

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.CompileOptions
import com.project.app.logic.Manuscript
import com.project.app.ui.common.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompileViewModel(
    private val repo: ProjectRepository,
    private val projectId: String
) : ViewModel() {

    private val _options = MutableStateFlow(CompileOptions())
    val options: StateFlow<CompileOptions> = _options.asStateFlow()

    private val _manuscript = MutableStateFlow<Manuscript?>(null)
    val manuscript: StateFlow<Manuscript?> = _manuscript.asStateFlow()

    init {
        recompile()
    }

    /**
     * Recompiled on every option change rather than on a "Compile" button.
     *
     * The options are the interesting part — what happens if I include the cut scenes? — and a
     * compile is a read over a few hundred rows. Making somebody press a button to see the answer
     * turns a question into a chore.
     */
    fun setOptions(options: CompileOptions) {
        _options.value = options
        recompile()
    }

    private fun recompile() = viewModelScope.launch {
        _manuscript.value = repo.search.compile(projectId, _options.value)
    }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CompileViewModel(repo, projectId) as T
    }
}

/**
 * The whole project as one document.
 *
 * The outline knows the order and the documents hold the text, so this screen assembles rather than
 * authors: nothing here is stored, and closing it throws the result away.
 *
 * What it will not do is hand you a clean-looking export with a hole in it. Pieces of the outline
 * with nothing written are listed by name before the preview, and documents belonging to no piece
 * are counted even when they are excluded — because the failure this screen exists to prevent is
 * discovering the missing scene *after* sending the file to somebody.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompileScreen(vm: CompileViewModel, onBack: () -> Unit) {
    val manuscript by vm.manuscript.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val text = manuscript?.render().orEmpty()
    // Split once, above the list: a novel-length manuscript is scrolled lazily by line rather than
    // composed as one enormous Text.
    val lines = remember(text) { text.lines() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("Compile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbars.showSnackbar("Copied ${manuscript?.summary.orEmpty()}") }
                        }
                    ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = { shareText(context, manuscript?.title ?: "Manuscript", text) }
                    ) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary") {
                SectionCard(title = manuscript?.title ?: "Compiling…") {
                    Text(
                        manuscript?.summary ?: "Reading the outline…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            item(key = "options") {
                SectionCard(title = "What goes in") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = options.includeHeadings,
                            onClick = { vm.setOptions(options.copy(includeHeadings = !options.includeHeadings)) },
                            label = { Text("Headings") }
                        )
                        FilterChip(
                            selected = options.includeSynopses,
                            onClick = { vm.setOptions(options.copy(includeSynopses = !options.includeSynopses)) },
                            label = { Text("Synopses") }
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = options.includeCut,
                            onClick = { vm.setOptions(options.copy(includeCut = !options.includeCut)) },
                            label = { Text("Cut material") }
                        )
                        FilterChip(
                            selected = options.includeUnplaced,
                            onClick = { vm.setOptions(options.copy(includeUnplaced = !options.includeUnplaced)) },
                            label = { Text("Unplaced docs") }
                        )
                    }
                }
            }

            manuscript?.gaps?.takeIf { it.isNotEmpty() }?.let { gaps ->
                item(key = "gaps") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null)
                                Text(
                                    "${gaps.size} with nothing written",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            gaps.take(12).forEach { gap ->
                                Text(
                                    "${gap.number}  ${gap.title}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (gaps.size > 12) {
                                Text("and ${gaps.size - 12} more", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            manuscript?.unplaced?.takeIf { it.isNotEmpty() && !options.includeUnplaced }?.let { loose ->
                item(key = "unplaced") {
                    SectionCard(title = "Not in the outline") {
                        Text(
                            "${loose.size} ${if (loose.size == 1) "document is" else "documents are"} " +
                                "not linked to anything and are not in this compile.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        loose.take(8).forEach {
                            Text(it.title, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item(key = "preview-title") {
                Text(
                    "Preview",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(lines.size, key = { "line-$it" }) { index ->
                Text(
                    lines[index].ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

private fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share manuscript")) }
}
