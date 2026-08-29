package com.project.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.ProjectRepository
import com.project.app.ui.docs.DocEditorScreen
import com.project.app.ui.docs.DocEditorViewModel
import com.project.app.ui.shelf.ShelfScreen
import com.project.app.ui.shelf.ShelfViewModel
import com.project.app.ui.theme.ProjectTheme
import com.project.app.ui.workspace.ProjectSection
import com.project.app.ui.workspace.ProjectWorkspace

private const val ROUTE_SHELF = "shelf"

/**
 * Project's single entry point: the shelf, one project open on it, and one document open in that.
 *
 * Three routes, and only three. The five sections of a project are *not* routes (see
 * `ProjectWorkspace`) — they are a way of looking at the same thing, and putting each on the back
 * stack would turn leaving a project into five taps of Back. What is on the stack is what Back
 * should undo: close the document, leave the project, and you are back on the shelf.
 *
 * Project has no `onStart` work and starts no services. It has no peer on the suite's sync seam to
 * reconcile with and nothing to remind you about — reminding you to do the work is LifeOps' job, and
 * it already does it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = ProjectApp.get(this)

        setContent {
            ProjectTheme {
                val nav = rememberNavController()
                ProjectNavGraph(nav = nav, repo = app.repository, prefs = app.prefs)
            }
        }
    }
}

@Composable
private fun ProjectNavGraph(
    nav: NavHostController,
    repo: ProjectRepository,
    prefs: ProjectPrefs
) {
    /**
     * Reopen on the project you left.
     *
     * Checked against the database first, because the project may have been deleted since — landing
     * somebody on a workspace for something that no longer exists is worse than landing them on the
     * shelf. It runs once per launch, not on every recomposition, so navigating back to the shelf
     * deliberately stays on the shelf.
     */
    LaunchedEffect(Unit) {
        val last = prefs.lastProjectId ?: return@LaunchedEffect
        if (repo.getProject(last) != null) {
            nav.navigate("project/$last")
        } else {
            prefs.lastProjectId = null
        }
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
                initialSection = ProjectSection.fromKey(prefs.lastSection),
                onSectionChange = { prefs.lastSection = it.key },
                onOpenDoc = { doc -> nav.navigate("project/$projectId/doc/${doc.id}") },
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
            val docId = entry.arguments?.getString("docId").orEmpty()
            val vm: DocEditorViewModel = viewModel(
                key = "doc-$docId",
                factory = DocEditorViewModel.Factory(repo, docId)
            )
            DocEditorScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
