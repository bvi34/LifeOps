package com.maintenance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maintenance.app.ui.asset.AssetDetailScreen
import com.maintenance.app.ui.asset.AssetDetailViewModel
import com.maintenance.app.ui.assets.AssetsScreen
import com.maintenance.app.ui.assets.AssetsViewModel
import com.maintenance.app.ui.due.DueScreen
import com.maintenance.app.ui.due.DueViewModel
import com.maintenance.app.ui.theme.MaintenanceTheme

/**
 * Maintenance's entry point: two lists and one thing at a time.
 *
 * The bottom bar has exactly two entries because the app answers exactly two questions — *what
 * needs doing?* and *what do I own?* — and a third destination would be a place to put things
 * rather than a place anybody goes. Which of the two you were last on is remembered, since somebody
 * who uses this as a register should not walk past the docket on every open.
 *
 * An asset's page is a route rather than a third tab: it is one thing rather than a list, and the
 * back gesture should return you to the list you came from, whichever it was.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = MaintenanceApp.get(this)

        setContent {
            MaintenanceTheme {
                MaintenanceShell(app)
            }
        }
    }
}

/**
 * Run a reconciliation round whenever Maintenance becomes visible.
 *
 * `ON_START` also fires the moment the observer is registered on an already-started lifecycle, so
 * this covers the first composition as well as every later return to the foreground — one trigger
 * rather than a `LaunchedEffect` for the first case and something else for all the others. (The
 * same shape People uses for its sync round, for the same reason: seven other apps share this
 * process, and walking to LifeOps and back does not recreate this activity.)
 */
@Composable
private fun SyncOnStart(app: MaintenanceApp) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) app.syncNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val ROUTE_DUE = "due"
private const val ROUTE_ASSETS = "assets"
private const val ROUTE_ASSET = "asset/{assetId}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceShell(app: MaintenanceApp) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    val prefs = remember { app.prefs }
    var showAll by rememberSaveable { mutableStateOf(prefs.docketShowsAll) }
    var showArchived by rememberSaveable { mutableStateOf(prefs.showArchived) }

    val onList = route == ROUTE_DUE || route == ROUTE_ASSETS

    // Reconcile with the LifeOps week whenever the app comes to the foreground. LifeOps announces a
    // tick as it happens, so this is not the mechanism — it is the backstop, and the reason nothing
    // depends on having caught a particular moment: a tick that landed while this app's database was
    // being restored, or a task deleted over there, is picked up the next time you look at this one.
    SyncOnStart(app)

    Scaffold(
        topBar = {
            // The asset page carries its own bar (it has a back arrow and a menu); the two lists
            // share this one.
            if (onList) {
                TopAppBar(title = { Text(if (route == ROUTE_ASSETS) "What you own" else "Maintenance") })
            }
        },
        bottomBar = {
            if (onList) {
                NavigationBar {
                    // The standard bottom-bar move: pop back to the graph's start and keep each
                    // list's scroll position, so switching tabs twice doesn't build a back stack
                    // you have to press through to leave the app.
                    fun switchTo(target: String) {
                        prefs.lastTab = target
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    NavigationBarItem(
                        selected = route == ROUTE_DUE,
                        onClick = { switchTo(ROUTE_DUE) },
                        icon = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
                        label = { Text("Due") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_ASSETS,
                        onClick = { switchTo(ROUTE_ASSETS) },
                        icon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                        label = { Text("Assets") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (prefs.lastTab == ROUTE_ASSETS) ROUTE_ASSETS else ROUTE_DUE,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_DUE) {
                val vm: DueViewModel = viewModel(factory = DueViewModel.Factory(app.repository))
                DueScreen(
                    vm = vm,
                    showAll = showAll,
                    onShowAllChange = { showAll = it; prefs.docketShowsAll = it },
                    onOpenAsset = { nav.navigate("asset/$it") }
                )
            }
            composable(ROUTE_ASSETS) {
                val vm: AssetsViewModel = viewModel(factory = AssetsViewModel.Factory(app.repository))
                AssetsScreen(
                    vm = vm,
                    showArchived = showArchived,
                    onShowArchivedChange = { showArchived = it; prefs.showArchived = it },
                    onOpenAsset = { nav.navigate("asset/$it") }
                )
            }
            composable(
                route = ROUTE_ASSET,
                arguments = listOf(navArgument("assetId") { type = NavType.StringType })
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getString("assetId").orEmpty()
                val vm: AssetDetailViewModel = viewModel(
                    factory = AssetDetailViewModel.Factory(app.repository, app.publisher, assetId)
                )
                AssetDetailScreen(vm = vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
