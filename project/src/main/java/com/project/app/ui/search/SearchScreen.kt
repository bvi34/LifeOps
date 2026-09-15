package com.project.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteTextField
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectSearch
import com.project.app.logic.SearchCorpus
import com.project.app.logic.SearchHit
import com.project.app.logic.SearchSection
import com.project.app.ui.common.EmptyState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    repo: ProjectRepository,
    projectId: String
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The corpus is loaded once and matched in memory on every keystroke.
     *
     * Combining the query *into* the flow rather than re-querying the database per keystroke is what
     * makes typing feel instant, and it keeps the matching rule in one place — the pure matcher —
     * instead of split between Kotlin and a pile of SQL `LIKE`s that could disagree with it.
     */
    private val corpus: StateFlow<SearchCorpus?> =
        repo.search.observeSearchCorpus(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hits: StateFlow<List<SearchHit>> =
        combine(corpus, _query) { loaded, query ->
            if (loaded == null) {
                emptyList()
            } else {
                ProjectSearch.search(query, loaded.outline, loaded.docs, loaded.lore, loaded.events, loaded.cards)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repo, projectId) as T
    }
}

/**
 * Search one project, across all five sections.
 *
 * Results are grouped by section and, inside each, named-after-it before merely-mentions-it. Tapping
 * a document opens it; tapping anything else takes you to the section it lives in — the honest limit
 * of a flat list, and better than a hit you cannot act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenDoc: (String) -> Unit,
    onJumpToSection: (SearchSection) -> Unit,
    onBack: () -> Unit
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()

    val focus = remember { FocusRequester() }
    // The only reason to be on this screen is to type, so the keyboard should already be up.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val grouped = ProjectSearch.grouped(hits)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "query") {
                SuiteTextField(
                    label = "Search this project",
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    // A query is not a sentence. Every screen that capitalises one makes somebody
                    // reach for shift-backspace before their first search of the day.
                    capitalise = KeyboardCapitalization.None,
                    modifier = Modifier.focusRequester(focus)
                )
            }

            if (query.isBlank()) {
                item(key = "prompt") {
                    EmptyState(
                        title = "Search the whole project",
                        detail = "Outline, documents, lore, timeline and board. Every word you type " +
                            "has to appear, but they can appear anywhere in the same thing."
                    )
                }
            } else if (hits.isEmpty()) {
                item(key = "none") {
                    EmptyState(
                        title = "Nothing found",
                        detail = "No outline piece, document, lore entry, event or card contains all " +
                            "of those words."
                    )
                }
            }

            grouped.forEach { (section, found) ->
                item(key = "header-${section.key}") {
                    Text(
                        "${section.label} · ${found.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(found, key = { "${section.key}-${it.id}" }) { hit ->
                    HitCard(
                        hit = hit,
                        onOpen = {
                            if (hit.section == SearchSection.DOCS && hit.parentId != null) {
                                onOpenDoc(hit.parentId)
                            } else {
                                onJumpToSection(hit.section)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HitCard(hit: SearchHit, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (hit.titleMatch) FontWeight.SemiBold else FontWeight.Normal
            )
            hit.snippet?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
