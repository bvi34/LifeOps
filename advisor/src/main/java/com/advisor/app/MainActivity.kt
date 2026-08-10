package com.advisor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.advisor.app.ui.AdvisorViewModel
import com.advisor.app.ui.ChatScreen
import com.advisor.app.ui.MemoryScreen
import com.advisor.app.ui.PermissionsScreen
import com.advisor.app.ui.ProfilesScreen
import com.advisor.app.ui.theme.AdvisorTheme

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    object Chat : Dest("chat", "Advisor", Icons.AutoMirrored.Filled.Chat)
    object Memory : Dest("memory", "Memory", Icons.Default.Bookmarks)
    object Profiles : Dest("profiles", "Profiles", Icons.Default.AccountTree)
    object Permissions : Dest("permissions", "Permissions", Icons.Default.Shield)
}

private val navItems = listOf(Dest.Chat, Dest.Memory, Dest.Profiles, Dest.Permissions)

/**
 * Advisor's single entry point: a two-tab shell — the chat and the permission gate — over the one
 * [AdvisorApp] runtime. Not a launcher; the Operations Sandbox home opens it explicitly.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AdvisorApp.get(this)

        setContent {
            AdvisorTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination
                // One runtime-backed ViewModel for the whole shell, so the top bar's clear-chat action
                // and the chat screen operate on the same conversation.
                val vm: AdvisorViewModel = viewModel(factory = AdvisorViewModel.Factory(app.repository))

                Scaffold(
                    topBar = {
                        AdvisorTopBar(
                            route = current?.route,
                            onClearChat = { vm.clearConversation() }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            navItems.forEach { dest ->
                                NavigationBarItem(
                                    selected = current?.hierarchy?.any { it.route == dest.route } == true,
                                    onClick = {
                                        nav.navigate(dest.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = Dest.Chat.route,
                        // consumeWindowInsets marks the Scaffold padding (which includes the bottom
                        // nav bar) as already applied, so a screen's imePadding lifts its input flush
                        // to the keyboard instead of leaving a nav-bar-height gap above it.
                        modifier = Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding)
                    ) {
                        composable(Dest.Chat.route) { ChatScreen(vm) }
                        composable(Dest.Memory.route) { MemoryScreen(vm) }
                        composable(Dest.Profiles.route) { ProfilesScreen(vm) }
                        composable(Dest.Permissions.route) { PermissionsScreen(vm) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvisorTopBar(route: String?, onClearChat: () -> Unit) {
    val title = navItems.firstOrNull { it.route == route }?.label ?: "Advisor"
    var confirmClear by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title) },
        actions = {
            // A clear-conversation action, offered only on the chat tab.
            if (route == Dest.Chat.route) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear conversation")
                }
            }
        }
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear conversation?") },
            text = {
                Text(
                    "This removes the chat history on this device. Your long-term memory, standing " +
                        "profiles and identity are untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearChat() }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}
