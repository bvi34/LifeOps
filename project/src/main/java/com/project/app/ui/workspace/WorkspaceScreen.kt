package com.project.app.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.project.app.data.model.Doc
import com.project.app.data.model.Project
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.SearchSection
import com.project.app.ui.board.BoardScreen
import com.project.app.ui.board.BoardViewModel
import com.project.app.ui.docs.DocsScreen
import com.project.app.ui.docs.DocsViewModel
import com.project.app.ui.lore.LoreScreen
import com.project.app.ui.lore.LoreViewModel
import com.project.app.ui.outline.OutlineScreen
import com.project.app.ui.outline.OutlineViewModel
import com.project.app.ui.timeline.TimelineScreen
import com.project.app.ui.timeline.TimelineViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The five ways of looking at one project.
 *
 * They are sections of a workspace, not tabs of an app: each answers a different question about the
 * same thing. **Outline** is what it is made of, **Docs** is the writing itself, **Lore** is what it
 * has to stay consistent with, **Timeline** is what happens in what order, and **Board** is what is
 * being done about it this week.
 */
enum class ProjectSection(
    /**
     * The section as the searcher names it.
     *
     * Held as the enum rather than as a matching string, so a search hit turns into a destination
     * through the type system: adding a section to one of these two enums and not the other becomes
     * a compile error instead of a jump that silently lands on the Outline.
     */
    val section: SearchSection,
    val icon: ImageVector
) {
    OUTLINE(SearchSection.OUTLINE, Icons.Filled.AccountTree),
    DOCS(SearchSection.DOCS, Icons.Filled.Description),
    LORE(SearchSection.LORE, Icons.AutoMirrored.Filled.MenuBook),
    TIMELINE(SearchSection.TIMELINE, Icons.Filled.Timeline),
    BOARD(SearchSection.BOARD, Icons.Filled.ViewKanban);

    val key: String get() = section.key
    val label: String get() = section.label

    companion object {
        /** Tolerant lookup for the remembered section; anything unknown opens on the Outline. */
        fun fromKey(key: String?): ProjectSection = entries.firstOrNull { it.key == key } ?: OUTLINE

        fun of(section: SearchSection): ProjectSection = entries.first { it.section == section }
    }
}

class WorkspaceViewModel(
    repo: ProjectRepository,
    projectId: String
) : ViewModel() {

    val project: StateFlow<Project?> =
        repo.observeProject(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkspaceViewModel(repo, projectId) as T
    }
}

/**
 * One project, open.
 *
 * The section is state rather than a navigation route, on purpose: switching from Docs to Board and
 * back is *looking at the same thing differently*, not going somewhere, and putting each on the back
 * stack would mean five taps of Back to leave a project you glanced at. Back leaves the project,
 * which is what the gesture means here.
 *
 * That state is **hoisted to the navigation graph** rather than kept here, because search needs to
 * set it: tapping a lore hit has to land you on Lore, and a section owned privately by this
 * composable could not be told to move.
 *
 * The section screens each get their own ViewModel keyed by the project — they observe different
 * tables and none of them needs the others' state, so a single workspace ViewModel would be five
 * unrelated flows in one object.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspace(
    repo: ProjectRepository,
    projectId: String,
    section: ProjectSection,
    onSectionChange: (ProjectSection) -> Unit,
    onOpenDoc: (Doc) -> Unit,
    onSearch: () -> Unit,
    onCompile: () -> Unit,
    onBack: () -> Unit,
    /** Ask for a hand-off round — see `BoardViewModel`. Passed down rather than fetched from the
     *  app singleton, so a screen still knows nothing about how the app is assembled. */
    onCardsChanged: () -> Unit = {}
) {
    val vm: WorkspaceViewModel = viewModel(
        key = "workspace-$projectId",
        factory = WorkspaceViewModel.Factory(repo, projectId)
    )
    val project by vm.project.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name?.let { "$it · ${section.label}" } ?: section.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects")
                    }
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search this project")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Project menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Compile…") },
                                onClick = {
                                    menuOpen = false
                                    onCompile()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                ProjectSection.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = candidate == section,
                        onClick = { onSectionChange(candidate) },
                        icon = { Icon(candidate.icon, contentDescription = candidate.label) },
                        label = { Text(candidate.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = project
            if (current == null) {
                // Either still loading, or the project was deleted from another screen. Saying so
                // beats an empty outline that looks like a project with nothing in it.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Opening…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Box
            }

            when (section) {
                ProjectSection.OUTLINE -> {
                    val outlineVm: OutlineViewModel = viewModel(
                        key = "outline-$projectId",
                        factory = OutlineViewModel.Factory(repo, projectId)
                    )
                    OutlineScreen(outlineVm, current.kind)
                }

                ProjectSection.DOCS -> {
                    val docsVm: DocsViewModel = viewModel(
                        key = "docs-$projectId",
                        factory = DocsViewModel.Factory(repo, projectId)
                    )
                    DocsScreen(docsVm, current, onOpenDoc = onOpenDoc)
                }

                ProjectSection.LORE -> {
                    val loreVm: LoreViewModel = viewModel(
                        key = "lore-$projectId",
                        factory = LoreViewModel.Factory(repo, projectId)
                    )
                    LoreScreen(loreVm)
                }

                ProjectSection.TIMELINE -> {
                    val timelineVm: TimelineViewModel = viewModel(
                        key = "timeline-$projectId",
                        factory = TimelineViewModel.Factory(repo, projectId)
                    )
                    TimelineScreen(timelineVm)
                }

                ProjectSection.BOARD -> {
                    val boardVm: BoardViewModel = viewModel(
                        key = "board-$projectId",
                        factory = BoardViewModel.Factory(repo, projectId, onCardsChanged)
                    )
                    BoardScreen(boardVm, current.kind)
                }
            }
        }
    }
}
