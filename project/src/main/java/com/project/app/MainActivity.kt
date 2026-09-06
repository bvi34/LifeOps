package com.project.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.ProjectDestination
import com.project.app.logic.ProjectLinks
import com.project.app.ui.compile.CompileScreen
import com.project.app.ui.compile.CompileViewModel
import com.project.app.ui.docs.DocEditorScreen
import com.project.app.ui.docs.DocEditorViewModel
import com.project.app.ui.docs.DocHistoryScreen
import com.project.app.ui.docs.DocHistoryViewModel
import com.project.app.ui.search.SearchScreen
import com.project.app.ui.search.SearchViewModel
import com.project.app.ui.shelf.ShelfScreen
import com.project.app.ui.shelf.ShelfViewModel
import com.project.app.ui.theme.ProjectTheme
import com.project.app.ui.workspace.ProjectSection
import com.project.app.ui.workspace.ProjectWorkspace

private const val ROUTE_SHELF = "shelf"

/**
 * Project's entry point: the shelf, a project open on it, and the three places you go *from* a
 * project — a document, the search, the compile.
 *
 * The five sections of a project are deliberately **not** routes (see `ProjectWorkspace`): they are
 * ways of looking at the same thing, and putting each on the back stack would turn leaving a project
 * into five taps of Back. What is on the stack is what Back should undo: close the document, leave
 * the project, and you are back on the shelf.
 *
 * The section itself is held here rather than inside the workspace, because search has to be able to
 * set it — tapping a lore result must land on Lore — and a section owned privately by the workspace
 * could not be told to move.
 *
 * Project has no `onStart` work and starts no services. It has no peer on the suite's sync seam to
 * reconcile with and nothing to remind you about — reminding you to do the work is LifeOps' job, and
 * it already does it.
 */
class MainActivity : ComponentActivity() {

    /**
     * Where a caller asked to open, until the nav graph has acted on it.
     *
     * State rather than a plain field so a new intent arriving at a running activity recomposes;
     * cleared once consumed so a rotation does not navigate a second time.
     */
    private var openDestination by mutableStateOf<ProjectDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = ProjectApp.get(this)
        openDestination = destinationOf(intent)

        setContent {
            ProjectTheme {
                val nav = rememberNavController()
                ProjectNavGraph(
                    nav = nav,
                    repo = app.repository,
                    prefs = app.prefs,
                    openDestination = openDestination,
                    onDestinationConsumed = { openDestination = null },
                    onCardsChanged = app::syncNow
                )
            }
        }
    }

    /**
     * Project is opened with [FLAG_ACTIVITY_CLEAR_TOP] and [FLAG_ACTIVITY_SINGLE_TOP] (see
     * [intentFor]), so a caller asking for a second destination reaches the instance already
     * running rather than stacking a new copy of the app on top of it — and the intent arrives
     * here instead of through `onCreate`.
     */
    /**
     * Reconcile the board's due dates with the week whenever Project comes forward.
     *
     * The completion bus catches ticks as they happen, but a week can also close, a task be deleted
     * or a date be edited while this app was not running — and a round is a reconciliation, so
     * running one on the way in reaches the right answer without having caught any of that.
     */
    override fun onStart() {
        super.onStart()
        ProjectApp.get(this).syncNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinationOf(intent)?.let { openDestination = it }
    }

    private fun destinationOf(intent: Intent?): ProjectDestination? =
        ProjectLinks.parse(intent?.getStringExtra(EXTRA_OPEN_DESTINATION))

    companion object {
        /**
         * Intent extra naming somewhere in Project to open at — an address in the vocabulary of
         * `logic/ProjectLinks`. Absent, or unrecognised, means "open normally".
         */
        const val EXTRA_OPEN_DESTINATION = "com.project.app.extra.OPEN_DESTINATION"

        /**
         * An intent that opens Project at [destination] — how anything in the suite links into it.
         *
         * Deliberately an **explicit** intent with no URL scheme and no exported filter beyond the
         * activity itself. Project requests no permissions and holds writing that never leaves the
         * device; a `project://` scheme would let any app on the phone address its rows, which is a
         * surface it has no reason to offer for a suite that shares one process.
         *
         * A destination that cannot be written down (see [ProjectLinks.format]) simply opens the
         * app, because failing to link somebody to a scene is a disappointment and failing to open
         * their writing is a bug.
         */
        fun intentFor(context: Context, destination: ProjectDestination): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    ProjectLinks.format(destination)?.let { putExtra(EXTRA_OPEN_DESTINATION, it) }
                }
    }
}

@Composable
private fun ProjectNavGraph(
    nav: NavHostController,
    repo: ProjectRepository,
    prefs: ProjectPrefs,
    openDestination: ProjectDestination? = null,
    onDestinationConsumed: () -> Unit = {},
    onCardsChanged: () -> Unit = {}
) {
    var sectionKey by rememberSaveable { mutableStateOf(prefs.lastSection ?: ProjectSection.OUTLINE.key) }
    val section = ProjectSection.fromKey(sectionKey)

    fun goToSection(next: ProjectSection) {
        sectionKey = next.key
        prefs.lastSection = next.key
    }

    /**
     * Go where an address points, once it has been checked against the database.
     *
     * Every way into this app names rows by id, and by the time one is opened the row may have been
     * deleted — Advisor can quote a document thrown away since, and "the project I had last time"
     * can name one deleted on another screen a minute ago. So an address is resolved first and a
     * stale one lands on the shelf, which is the whole reason this is one function rather than two
     * similar ones: the remembered place is exercised on every launch and a link is exercised
     * rarely, and sharing the path means the rare one is not the untested one.
     */
    suspend fun goTo(destination: ProjectDestination): Boolean {
        val resolved = repo.resolve(destination) ?: return false

        when (resolved) {
            // Asked for by name, so it means "show me the shelf" rather than "stay wherever you
            // are" — which matters when the app is already open on a project.
            is ProjectDestination.Shelf -> nav.popBackStack(ROUTE_SHELF, inclusive = false)

            is ProjectDestination.Workspace -> {
                resolved.section?.let { goToSection(ProjectSection.of(it)) }
                prefs.lastProjectId = resolved.projectId
                nav.navigate("project/${resolved.projectId}") { launchSingleTop = true }
            }

            is ProjectDestination.Document -> {
                prefs.lastProjectId = resolved.projectId
                // The project first, so Back from the document lands in the workspace rather than
                // out of the app — what this app's own routes promise Back means.
                nav.navigate("project/${resolved.projectId}") { launchSingleTop = true }
                nav.navigate("project/${resolved.projectId}/doc/${resolved.docId}") {
                    // A second link to the document already open is a no-op rather than a second
                    // copy of it on the back stack behind the first.
                    launchSingleTop = true
                }
            }
        }
        return true
    }

    /**
     * What the app was started with, fixed at the first composition.
     *
     * Held separately from the live [openDestination] because these are two different questions:
     * "was this launch a link?" is asked once and never changes, while "has a *new* link arrived?"
     * is asked every time the activity is handed another intent.
     */
    val launchedWith = remember { openDestination }

    /**
     * Reopen on the project you left — unless this launch was a link, in which case the caller has
     * already said where to be and second-guessing them would be worse than useless.
     *
     * Keyed on `Unit`, so it runs once per launch: navigating back to the shelf by hand
     * deliberately stays on the shelf.
     */
    LaunchedEffect(Unit) {
        if (launchedWith != null) return@LaunchedEffect
        val last = prefs.lastProjectId ?: return@LaunchedEffect
        // Forget a project that has been deleted, so the next launch does not ask the same dead
        // question — `goTo` reports whether the address was still good.
        if (!goTo(ProjectDestination.Workspace(last))) prefs.lastProjectId = null
    }

    /**
     * Honour a link, including one that arrives at an activity already running.
     *
     * Cleared once acted on, which re-runs this effect with nothing to do — that is the reason the
     * restore above is a separate effect keyed on `Unit` rather than a fallback inside this one. As
     * a fallback it would fire again the moment a link was consumed, and push the workspace back on
     * top of the document the link had just opened.
     */
    LaunchedEffect(openDestination) {
        val asked = openDestination ?: return@LaunchedEffect
        goTo(asked)
        onDestinationConsumed()
    }

    NavHost(navController = nav, startDestination = ROUTE_SHELF) {
        composable(ROUTE_SHELF) {
            val vm: ShelfViewModel = viewModel(factory = ShelfViewModel.Factory(repo))
            ShelfScreen(vm) { project ->
                prefs.lastProjectId = project.id
                nav.navigate("project/${project.id}")
            }
        }

        composable(
            route = "project/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            ProjectWorkspace(
                repo = repo,
                projectId = projectId,
                section = section,
                onSectionChange = ::goToSection,
                onOpenDoc = { doc -> nav.navigate("project/$projectId/doc/${doc.id}") },
                onSearch = { nav.navigate("project/$projectId/search") },
                onCompile = { nav.navigate("project/$projectId/compile") },
                onCardsChanged = onCardsChanged,
                onBack = {
                    prefs.lastProjectId = null
                    nav.popBackStack()
                }
            )
        }

        composable(
            route = "project/{projectId}/doc/{docId}",
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType }
            )
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val docId = entry.arguments?.getString("docId").orEmpty()
            val vm: DocEditorViewModel = viewModel(
                key = "doc-$docId",
                factory = DocEditorViewModel.Factory(repo, prefs, docId)
            )
            DocEditorScreen(
                vm,
                // The history is a route rather than a sheet over the editor: reading a version is
                // reading a document, and Back from it should mean "back to the document" rather
                // than dismissing something that was covering it.
                onHistory = { nav.navigate("project/$projectId/doc/$docId/history") },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "project/{projectId}/doc/{docId}/history",
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType }
            )
        ) { entry ->
            val docId = entry.arguments?.getString("docId").orEmpty()
            val vm: DocHistoryViewModel = viewModel(
                key = "history-$docId",
                factory = DocHistoryViewModel.Factory(repo, docId)
            )
            DocHistoryScreen(vm, onBack = { nav.popBackStack() })
        }

        composable(
            route = "project/{projectId}/search",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val vm: SearchViewModel = viewModel(
                key = "search-$projectId",
                factory = SearchViewModel.Factory(repo, projectId)
            )
            SearchScreen(
                vm = vm,
                // A document opens directly. Anything else lands on the section that holds it and
                // leaves the search behind, which is why both paths pop first.
                onOpenDoc = { docId ->
                    nav.popBackStack()
                    nav.navigate("project/$projectId/doc/$docId")
                },
                onJumpToSection = { found ->
                    goToSection(ProjectSection.of(found))
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "project/{projectId}/compile",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val vm: CompileViewModel = viewModel(
                key = "compile-$projectId",
                factory = CompileViewModel.Factory(repo, projectId)
            )
            CompileScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
