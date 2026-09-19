package com.secrets.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.operations.vaultkit.VaultState
import com.secrets.app.ui.audit.AuditScreen
import com.secrets.app.ui.generator.GeneratorScreen
import com.secrets.app.ui.importer.ImportScreen
import com.secrets.app.ui.importer.ImportViewModel
import com.secrets.app.ui.item.ItemScreen
import com.secrets.app.ui.item.ItemViewModel
import com.secrets.app.ui.items.ItemsScreen
import com.secrets.app.ui.items.ItemsViewModel
import com.secrets.app.ui.settings.SecretsSettingsScreen
import com.secrets.app.ui.theme.SecretsTheme
import com.secrets.app.ui.unlock.UnlockScreen
import com.secrets.app.ui.unlock.UnlockViewModel

/**
 * Secrets' entry point: a door, and three lists behind it.
 *
 * ## FLAG_SECURE
 *
 * Set for the life of this activity, which is the one piece of Android-specific hardening this app
 * genuinely needs. Without it the system writes a screenshot of whatever is on screen when the app
 * goes to the background, to draw the recents card — so a vault list, or an item with its password
 * revealed, ends up as a file on disk taken by the platform rather than by anybody's decision. It
 * also stops screenshots and screen recording while this app is in front.
 *
 * It is set unconditionally rather than only while unlocked: the flag applies to the *window*, and a
 * window that gains and loses it as the vault opens and shuts is a window that occasionally gets
 * captured mid-transition.
 *
 * ## The lock, and where it is decided
 *
 * Two rules, both here rather than in a background job — nothing in this app runs when it is not on
 * screen, and a scheduled task that closed a vault would be a scheduled task that knows when one is
 * open:
 *
 *  - **on leaving**, if the household asked for that;
 *  - **on returning**, if more than the auto-lock time has passed. That is the honest place to check
 *    it: a timer running while the app is backgrounded would be a wake-up nobody asked for, and the
 *    only moment the difference is observable is the moment somebody comes back.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val app = SecretsApp.get(this)

        setContent {
            SecretsTheme {
                SecretsShell(app)
            }
        }
    }
}

private const val ROUTE_UNLOCK = "unlock"
private const val ROUTE_ITEMS = "items"
private const val ROUTE_GENERATOR = "generator"
private const val ROUTE_AUDIT = "audit"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_IMPORT = "import"
private const val ROUTE_ITEM = "item/{itemId}"
private const val ROUTE_NEW_ITEM = "item/new"

private val TABS = setOf(ROUTE_ITEMS, ROUTE_GENERATOR, ROUTE_AUDIT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretsShell(app: SecretsApp) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val vaultState by app.vault.state.collectAsStateWithLifecycle()
    val prefs = remember { app.prefs }

    LockOnLifecycle(app)

    // The whole app is behind the door: when the vault shuts — by the timer, by the button, or
    // because another screen locked it — every route collapses back to the unlock screen. A back
    // stack that survived a lock would be a back stack somebody could press their way into.
    androidx.compose.runtime.LaunchedEffect(vaultState) {
        if (vaultState != VaultState.UNLOCKED && route != ROUTE_UNLOCK) {
            nav.navigate(ROUTE_UNLOCK) {
                // Inclusive of the start destination, which *is* the unlock screen: what is being
                // cleared is every item screen stacked on top of it, along with the saved state
                // Compose would otherwise restore — a list that came back showing the vault's
                // contents after a lock would make the lock decorative.
                popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val onTab = route in TABS

    Scaffold(
        topBar = {
            if (route != ROUTE_UNLOCK) {
                TopAppBar(
                    title = {
                        Text(
                            when (route) {
                                ROUTE_GENERATOR -> "Generate"
                                ROUTE_AUDIT -> "Check"
                                ROUTE_SETTINGS -> "Settings"
                                ROUTE_IMPORT -> "Import"
                                ROUTE_ITEM, ROUTE_NEW_ITEM -> "Item"
                                else -> "Secrets"
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
                        }
                        IconButton(onClick = { app.vault.lock() }) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock the vault")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (onTab) {
                NavigationBar {
                    fun switchTo(target: String) {
                        prefs.lastTab = target
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }

                    NavigationBarItem(
                        selected = route == ROUTE_ITEMS,
                        onClick = { switchTo(ROUTE_ITEMS) },
                        icon = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
                        label = { Text("Vault") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_GENERATOR,
                        onClick = { switchTo(ROUTE_GENERATOR) },
                        icon = { Icon(Icons.Filled.Casino, contentDescription = null) },
                        label = { Text("Generate") }
                    )
                    NavigationBarItem(
                        selected = route == ROUTE_AUDIT,
                        onClick = { switchTo(ROUTE_AUDIT) },
                        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        label = { Text("Check") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (route == ROUTE_ITEMS) {
                FloatingActionButton(onClick = { nav.navigate(ROUTE_NEW_ITEM) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add an item")
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_UNLOCK,
            modifier = Modifier.padding(padding)
        ) {
            composable(ROUTE_UNLOCK) {
                val vm: UnlockViewModel = viewModel(factory = UnlockViewModel.Factory(app.vault))
                UnlockScreen(vm = vm, onOpened = {
                    val target = prefs.lastTab?.takeIf { it in TABS } ?: ROUTE_ITEMS
                    nav.navigate(target) { popUpTo(ROUTE_UNLOCK) { inclusive = true } }
                })
            }
            composable(ROUTE_ITEMS) {
                val vm: ItemsViewModel = viewModel(factory = ItemsViewModel.Factory(app.vault, prefs))
                ItemsScreen(vm = vm, store = app.vault, onOpen = { nav.navigate("item/$it") })
            }
            composable(ROUTE_GENERATOR) {
                GeneratorScreen(prefs = prefs)
            }
            composable(ROUTE_AUDIT) {
                AuditScreen(store = app.vault, onOpen = { nav.navigate("item/$it") })
            }
            composable(ROUTE_SETTINGS) {
                SecretsSettingsScreen(
                    store = app.vault,
                    prefs = prefs,
                    onImport = { nav.navigate(ROUTE_IMPORT) }
                )
            }
            composable(ROUTE_IMPORT) {
                val vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory(app.vault))
                ImportScreen(vm = vm, onDone = { nav.popBackStack() })
            }
            composable(ROUTE_NEW_ITEM) {
                val vm: ItemViewModel = viewModel(factory = ItemViewModel.Factory(app.vault, null))
                ItemScreen(vm = vm, prefs = prefs, onDone = { nav.popBackStack() })
            }
            composable(
                route = ROUTE_ITEM,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
                val vm: ItemViewModel = viewModel(factory = ItemViewModel.Factory(app.vault, itemId))
                ItemScreen(vm = vm, prefs = prefs, onDone = { nav.popBackStack() })
            }
        }
    }
}

/** The two lock rules, hung off this activity's lifecycle. See the class note. */
@Composable
private fun LockOnLifecycle(app: SecretsApp) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (app.prefs.lockOnLeave) app.vault.lock()
                Lifecycle.Event.ON_START ->
                    if (app.vault.shouldAutoLock(System.currentTimeMillis(), app.prefs.autoLockMillis)) {
                        app.vault.lock()
                    } else {
                        app.vault.touch()
                    }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
