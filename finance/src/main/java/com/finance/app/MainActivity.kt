package com.finance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.finance.app.ui.account.AccountDetailScreen
import com.finance.app.ui.account.AccountDetailViewModel
import com.finance.app.ui.accounts.AccountsScreen
import com.finance.app.ui.accounts.AccountsViewModel
import com.finance.app.ui.activity.ActivityScreen
import com.finance.app.ui.activity.ActivityViewModel
import com.finance.app.ui.connections.ConnectionsScreen
import com.finance.app.ui.connections.ConnectionsViewModel
import com.finance.app.ui.due.DueScreen
import com.finance.app.ui.due.DueViewModel
import com.finance.app.ui.picture.PictureScreen
import com.finance.app.ui.picture.PictureViewModel
import com.finance.app.ui.settings.FinanceSettingsScreen
import com.finance.app.ui.theme.FinanceTheme

/**
 * Finance's entry point: four lists and one door.
 *
 * The bottom bar has one entry per question the app answers — *how am I doing?*, *what's due?*,
 * *what have I got?* and *what happened?* — and no more. Connections is not a fifth tab because it
 * is not a question: it is set-up, visited twice a year, and a tab spent on it would be a quarter of
 * the bar spent on something nobody needs on a Tuesday.
 *
 * Which tab you were last on is remembered. Somebody who opens this app to check whether the
 * mortgage cleared should not have to walk past the summary to get there.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = FinanceApp.get(this)

        setContent {
            FinanceTheme {
                FinanceShell(app)
            }
        }
    }
}

/**
 * Refresh and reconcile whenever Finance becomes visible.
 *
 * `ON_START` also fires the moment the observer is registered on an already-started lifecycle, so
 * this covers the first composition as well as every later return to the foreground — one trigger
 * rather than a `LaunchedEffect` for the first case and something else for all the others. (The same
 * shape Maintenance uses for its upkeep round, for the same reason: the suite's other apps share this
 * process, and walking to LifeOps and back does not recreate this activity.)
 *
 * The refresh throttles itself — see [com.finance.app.data.prefs.FinancePrefs.shouldAutoRefresh] —
 * so opening the app twice in a minute asks the bank once.
 */
@Composable
private fun RefreshOnStart(app: FinanceApp) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) app.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val ROUTE_PICTURE = "picture"
private const val ROUTE_DUE = "due"
private const val ROUTE_ACCOUNTS = "accounts"
private const val ROUTE_ACTIVITY = "activity"
private const val ROUTE_CONNECTIONS = "connections"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ACCOUNT = "account/{accountId}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinanceShell(app: FinanceApp) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val prefs = remember { app.prefs }

    val onTab = route in TABS

    RefreshOnStart(app)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            ROUTE_DUE -> "Due"
                            ROUTE_ACCOUNTS -> "Accounts"
                            ROUTE_ACTIVITY -> "Activity"
                            ROUTE_CONNECTIONS -> "Connections"
                            ROUTE_SETTINGS -> "Settings"
                            // The account page titles itself from the account, in its own first
                            // card — repeating the name up here would say it twice on one screen.
                            ROUTE_ACCOUNT -> "Account"
                            else -> "Finance"
                        }
                    )
                },
                navigationIcon = {
                    if (!onTab) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onTab) {
                        IconButton(onClick = { nav.navigate(ROUTE_SETTINGS) }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Settings")
                        }
                        IconButton(onClick = { nav.navigate(ROUTE_CONNECTIONS) }) {
                            Icon(Icons.Filled.Link, contentDescription = "Connections")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    // The standard bottom-bar move: pop back to the graph's start and keep each
                    // list's scroll position, so switching tabs twice doesn't build a back stack you
                    // have to press through to leave the app.
                    fun switchTo(target: String) {
                        prefs.lastTab = target
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    NavigationBarItem(
                        selected = route == ROUTE_PICTURE,
                        onClick = { switchTo(ROUTE_PICTURE) },
                        icon = { Icon(Icons.Filled.Insights, contentDescription = null) },
                        label = { Text("Picture") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_DUE,
                        onClick = { switchTo(ROUTE_DUE) },
                        icon = { Icon(Icons.Filled.EventAvailable, contentDescription = null) },
                        label = { Text("Due") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_ACCOUNTS,
                        onClick = { switchTo(ROUTE_ACCOUNTS) },
                        icon = { Icon(Icons.Filled.AccountBalance, contentDescription = null) },
                        label = { Text("Accounts") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_ACTIVITY,
                        onClick = { switchTo(ROUTE_ACTIVITY) },
                        icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
                        label = { Text("Activity") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = prefs.lastTab?.takeIf { it in TABS } ?: ROUTE_PICTURE,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_PICTURE) {
                val vm: PictureViewModel =
                    viewModel(factory = PictureViewModel.Factory(app.repository, app.prefs))
                PictureScreen(
                    vm = vm,
                    onOpenDue = { nav.navigate(ROUTE_DUE) },
                    onOpenConnections = { nav.navigate(ROUTE_CONNECTIONS) }
                )
            }
            composable(ROUTE_DUE) {
                val vm: DueViewModel = viewModel(
                    factory = DueViewModel.Factory(app.repository, app.publisher, app.prefs)
                )
                DueScreen(vm = vm)
            }
            composable(ROUTE_ACCOUNTS) {
                val vm: AccountsViewModel = viewModel(factory = AccountsViewModel.Factory(app.repository))
                // An account's own page is not built yet; tapping a row does nothing rather than
                // navigating somewhere empty. The Activity tab already answers "what happened on
                // this account" for the whole household, which is most of what a detail page is for.
                AccountsScreen(vm = vm, onOpenAccount = { nav.navigate("account/$it") })
            }
            composable(ROUTE_ACTIVITY) {
                val vm: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory(app.repository))
                ActivityScreen(vm = vm)
            }
            composable(
                route = ROUTE_ACCOUNT,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType })
            ) { backStackEntry ->
                val accountId = backStackEntry.arguments?.getString("accountId").orEmpty()
                val vm: AccountDetailViewModel = viewModel(
                    factory = AccountDetailViewModel.Factory(app.repository, accountId)
                )
                AccountDetailScreen(vm = vm)
            }
            composable(ROUTE_SETTINGS) {
                // No view model: three preferences read and written directly, with nothing derived
                // from them here. A ViewModel would exist only to hold a copy of what the prefs
                // already hold.
                FinanceSettingsScreen(prefs = app.prefs)
            }
            composable(ROUTE_CONNECTIONS) {
                val vm: ConnectionsViewModel = viewModel(
                    factory = ConnectionsViewModel.Factory(app.repository, app.secrets, app.sync)
                )
                ConnectionsScreen(vm = vm)
            }
        }
    }
}

private val TABS = setOf(ROUTE_PICTURE, ROUTE_DUE, ROUTE_ACCOUNTS, ROUTE_ACTIVITY)
