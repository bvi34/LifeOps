package com.lifeops.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeops.app.ui.screens.reports.ReportsScreen
import com.lifeops.app.ui.screens.reports.ReportsViewModelFactory
import com.lifeops.app.ui.screens.resources.ResourcesScreen
import com.lifeops.app.ui.screens.resources.ResourcesViewModelFactory
import com.lifeops.app.ui.screens.settings.SettingsScreen
import com.lifeops.app.ui.screens.settings.SettingsViewModelFactory
import com.lifeops.app.ui.screens.thisweek.ThisWeekScreen
import com.lifeops.app.ui.screens.thisweek.ThisWeekViewModelFactory
import com.lifeops.app.ui.theme.LifeOpsTheme

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object ThisWeek : Screen("this_week", "This Week", Icons.Default.CalendarToday)
    object Resources : Screen("resources", "Resources", Icons.Default.Diamond)
    object Reports : Screen("reports", "Reports", Icons.Default.BarChart)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.ThisWeek, Screen.Resources, Screen.Reports, Screen.Settings)

class MainActivity : ComponentActivity() {

    private var showNotificationDeniedDialog by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) showNotificationDeniedDialog = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("lifeops_prefs", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!prefs.getBoolean("notification_permission_requested", false)) {
                prefs.edit().putBoolean("notification_permission_requested", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // SCHEDULE_EXACT_ALARM is a special permission on Android 12+ that requires the user
        // to grant it via system Settings; it cannot be requested via requestPermissions().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!prefs.getBoolean("exact_alarm_permission_requested", false)) {
                prefs.edit().putBoolean("exact_alarm_permission_requested", true).apply()
                val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }
            }
        }

        val app = application as LifeOpsApp
        val sharedText = intent.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            LifeOpsTheme {
                LifeOpsNavHost(app, sharedText)
                if (showNotificationDeniedDialog) {
                    AlertDialog(
                        onDismissRequest = { showNotificationDeniedDialog = false },
                        title = { Text("Notifications disabled") },
                        text = { Text("Task reminders won't work. You can enable notifications for LifeOps in System Settings → Apps.") },
                        confirmButton = {
                            TextButton(onClick = { showNotificationDeniedDialog = false }) { Text("OK") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LifeOpsNavHost(app: LifeOpsApp, sharedText: String? = null) {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.ThisWeek.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.ThisWeek.route) {
                val vm = viewModel<com.lifeops.app.ui.screens.thisweek.ThisWeekViewModel>(
                    factory = ThisWeekViewModelFactory(
                        app,
                        app.applicationScope,
                        app.weekRepository, app.taskRepository, app.aspectRepository, app.importRepository,
                        app.taskNoteRepository, app.timeEntryRepository, app.notificationRepository,
                        app.costResourceRepository, app.projectRepository
                    )
                )
                // Inject shared text if coming from share sheet
                LaunchedEffect(sharedText) {
                    if (!sharedText.isNullOrBlank()) {
                        vm.onImportJsonChange(sharedText)
                        vm.openImportDialog()
                    }
                }
                ThisWeekScreen(vm)
            }
            composable(Screen.Resources.route) {
                val vm = viewModel<com.lifeops.app.ui.screens.resources.ResourcesViewModel>(
                    factory = ResourcesViewModelFactory(
                        app.gameResourceRepository, app.aspectRepository, app.taskRepository, app.weekRepository, app.timeEntryRepository
                    )
                )
                ResourcesScreen(vm)
            }
            composable(Screen.Reports.route) {
                val vm = viewModel<com.lifeops.app.ui.screens.reports.ReportsViewModel>(
                    factory = ReportsViewModelFactory(
                        app.weekRepository, app.aspectRepository,
                        app.taskRepository, app.timeEntryRepository, app.costResourceRepository,
                        app.projectRepository
                    )
                )
                ReportsScreen(vm)
            }
            composable(Screen.Settings.route) {
                val vm = viewModel<com.lifeops.app.ui.screens.settings.SettingsViewModel>(
                    factory = SettingsViewModelFactory(
                        app.aspectRepository, app.gameResourceRepository,
                        app.preferencesRepository, app.backupRepository, app.taskRepository,
                        app.costResourceRepository, app.projectRepository
                    )
                )
                SettingsScreen(vm)
            }
        }
    }
}
