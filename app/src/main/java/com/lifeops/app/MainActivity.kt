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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifeops.app.ui.components.AppHeaderViewModel
import com.lifeops.app.ui.components.AppHeaderViewModelFactory
import com.lifeops.app.ui.components.LocalSardonicMessage
import com.lifeops.app.ui.components.WelcomeDialog
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.lifeops.app.ui.screens.counters.CounterDetailScreen
import com.lifeops.app.ui.screens.counters.CounterDetailViewModelFactory
import com.lifeops.app.ui.screens.counters.CountersScreen
import com.lifeops.app.ui.screens.counters.CountersViewModelFactory
import com.lifeops.app.ui.screens.growth.GrowthScreen
import com.lifeops.app.ui.screens.growth.GrowthViewModelFactory
import com.lifeops.app.ui.screens.history.HistoryScreen
import com.lifeops.app.ui.screens.planning.CostResourcesScreen
import com.lifeops.app.ui.screens.planning.PeopleScreen
import com.lifeops.app.ui.screens.planning.PeopleViewModelFactory
import com.lifeops.app.ui.screens.planning.PersonDetailScreen
import com.lifeops.app.ui.screens.planning.PersonDetailViewModelFactory
import com.lifeops.app.ui.screens.planning.PlanningScreen
import com.lifeops.app.ui.screens.planning.ProjectsScreen
import com.lifeops.app.ui.screens.planning.RunbooksScreen
import com.lifeops.app.ui.screens.planning.TemplatesScreen
import com.lifeops.app.ui.screens.projectdetail.ProjectDetailScreen
import com.lifeops.app.ui.screens.projectdetail.ProjectDetailViewModelFactory
import com.lifeops.app.ui.screens.reports.ReportsScreen
import com.lifeops.app.ui.screens.reports.ReportsViewModelFactory
import com.lifeops.app.ui.screens.resources.ResourcesScreen
import com.lifeops.app.ui.screens.resources.ResourcesViewModelFactory
import com.lifeops.app.ui.screens.settings.SettingsScreen
import com.lifeops.app.ui.screens.weather.WeatherScreen
import com.lifeops.app.ui.screens.weather.WeatherViewModelFactory
import com.lifeops.app.ui.screens.settings.SettingsViewModel
import com.lifeops.app.ui.screens.settings.SettingsViewModelFactory
import com.lifeops.app.ui.screens.thisweek.ThisWeekViewModelFactory
import com.lifeops.app.ui.theme.LifeOpsTheme

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object ThisWeek : Screen("this_week", "This Week", Icons.Default.CalendarToday)
    object Planning : Screen("planning", "Planning", Icons.Default.Checklist)
    object Game : Screen("game", "Game", Icons.Default.SportsEsports)
    object History : Screen("history", "History", Icons.Default.History)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

// Game sits in the middle — the present-tense payoff between forward-looking Planning and the
// backward-looking History record.
val bottomNavItems = listOf(Screen.ThisWeek, Screen.Planning, Screen.Game, Screen.History, Screen.Settings)

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
            val themePreset by app.preferencesRepository.themePresetFlow.collectAsStateWithLifecycle()
            val isDarkMode by app.preferencesRepository.darkModeFlow.collectAsStateWithLifecycle()
            val customPalette by app.preferencesRepository.customPaletteFlow.collectAsStateWithLifecycle()
            LifeOpsTheme(preset = themePreset, darkMode = isDarkMode, customPalette = customPalette) {
                LifeOpsNavHost(app, sharedText)
                var showWelcome by remember { mutableStateOf(!app.preferencesRepository.onboardingShown) }
                if (showWelcome) {
                    WelcomeDialog(onDismiss = {
                        app.preferencesRepository.onboardingShown = true
                        showWelcome = false
                    })
                }
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
    // One factory shared by Settings and the Planning management screens (projects,
    // runbooks, templates, cost resources). Each destination still gets its own
    // ViewModel instance scoped to its back-stack entry.
    val settingsVmFactory = remember {
        SettingsViewModelFactory(
            app.aspectRepository, app.gameResourceRepository,
            app.preferencesRepository, app.backupRepository, app.taskRepository,
            app.costResourceRepository, app.projectRepository, app.growthRepository,
            app.runbookRepository, app.templateRepository, app.foodItemRepository
        )
    }
    val headerVm = viewModel<AppHeaderViewModel>(
        factory = AppHeaderViewModelFactory(app.weekRepository, app.taskRepository)
    )
    val sardonicMessage by headerVm.sardonicMessage.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalSardonicMessage provides sardonicMessage) {
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
            // This Week — Tasks/Daily Plan/Collection hub, plus the Collection detail screens
            // (recipes, books, future projects) it drills into.
            navigation(startDestination = "this_week_hub", route = Screen.ThisWeek.route) {
                composable("this_week_hub") {
                    val taskManagerVm = viewModel<com.lifeops.app.ui.screens.thisweek.ThisWeekViewModel>(
                        factory = ThisWeekViewModelFactory(
                            app,
                            app.applicationScope,
                            app.weekRepository, app.taskRepository, app.aspectRepository, app.importRepository,
                            app.taskNoteRepository, app.timeEntryRepository, app.notificationRepository,
                            app.costResourceRepository, app.projectRepository, app.preferencesRepository,
                            app.runbookRepository, app.templateRepository, app.counterRepository,
                            app.weatherRepository, app.personRepository
                        )
                    )
                    val dailyPlanVm = viewModel<com.lifeops.app.ui.screens.dailyplan.DailyPlanViewModel>(
                        factory = com.lifeops.app.ui.screens.dailyplan.DailyPlanViewModelFactory(
                            app.foodLogRepository, app.foodItemRepository
                        )
                    )
                    val recipeVm = viewModel<com.lifeops.app.ui.screens.collection.RecipeViewModel>(
                        factory = com.lifeops.app.ui.screens.collection.RecipeViewModelFactory(app.recipeRepository)
                    )
                    val bookVm = viewModel<com.lifeops.app.ui.screens.collection.BookViewModel>(
                        factory = com.lifeops.app.ui.screens.collection.BookViewModelFactory(app.bookRepository)
                    )
                    val futureProjectVm = viewModel<com.lifeops.app.ui.screens.collection.FutureProjectViewModel>(
                        factory = com.lifeops.app.ui.screens.collection.FutureProjectViewModelFactory(app.futureProjectRepository)
                    )
                    com.lifeops.app.ui.screens.weekhub.WeekHubScreen(
                        dailyPlanViewModel = dailyPlanVm,
                        taskManagerViewModel = taskManagerVm,
                        recipeViewModel = recipeVm,
                        bookViewModel = bookVm,
                        futureProjectViewModel = futureProjectVm,
                        onOpenRecipe = { id -> navController.navigate("recipe_detail/$id") },
                        onOpenBook = { id -> navController.navigate("book_detail/$id") },
                        onOpenFutureProject = { id -> navController.navigate("future_project_detail/$id") },
                        onOpenProject = { id -> navController.navigate("project_detail/$id") },
                        onOpenPerson = { id -> navController.navigate("person_detail/$id") },
                        onOpenCounter = { id -> navController.navigate("counter_detail/$id") },
                        sharedText = sharedText,
                        onImportShared = { text ->
                            taskManagerVm.onImportJsonChange(text)
                            taskManagerVm.openImportDialog()
                        }
                    )
                }
                composable("recipe_detail/{recipeId}") { backStackEntry ->
                    val recipeId = backStackEntry.arguments?.getString("recipeId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.collection.RecipeDetailViewModel>(
                        key = "recipe_detail_$recipeId",
                        factory = com.lifeops.app.ui.screens.collection.RecipeDetailViewModelFactory(
                            recipeId, app.recipeRepository, app.foodItemRepository
                        )
                    )
                    com.lifeops.app.ui.screens.collection.RecipeDetailScreen(vm) { navController.navigateUp() }
                }
                composable("book_detail/{bookId}") { backStackEntry ->
                    val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.collection.BookDetailViewModel>(
                        key = "book_detail_$bookId",
                        factory = com.lifeops.app.ui.screens.collection.BookDetailViewModelFactory(bookId, app.bookRepository)
                    )
                    com.lifeops.app.ui.screens.collection.BookDetailScreen(vm) { navController.navigateUp() }
                }
                composable("future_project_detail/{projectId}") { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.collection.FutureProjectDetailViewModel>(
                        key = "future_project_detail_$projectId",
                        factory = com.lifeops.app.ui.screens.collection.FutureProjectDetailViewModelFactory(
                            projectId, app.futureProjectRepository, app.projectRepository
                        )
                    )
                    com.lifeops.app.ui.screens.collection.FutureProjectDetailScreen(vm) { navController.navigateUp() }
                }
            }

            // Planning — forward-looking management screens, reached via a hub.
            navigation(startDestination = "planning_hub", route = Screen.Planning.route) {
                composable("planning_hub") {
                    PlanningScreen(
                        onOpenFutureTasks = { navController.navigate("future_tasks") },
                        onOpenProjects = { navController.navigate("projects") },
                        onOpenCounters = { navController.navigate("counters") },
                        onOpenRunbooks = { navController.navigate("runbooks") },
                        onOpenTemplates = { navController.navigate("templates") },
                        onOpenCostResources = { navController.navigate("cost_resources") },
                        onOpenPeople = { navController.navigate("people") },
                        onOpenWeather = { navController.navigate("weather") },
                        onOpenActivities = { navController.navigate("activities") }
                    )
                }
                composable("future_tasks") {
                    val vm = viewModel<com.lifeops.app.ui.screens.planning.FutureTasksViewModel>(
                        factory = com.lifeops.app.ui.screens.planning.FutureTasksViewModelFactory(
                            app.taskRepository, app.aspectRepository
                        )
                    )
                    com.lifeops.app.ui.screens.planning.FutureTasksScreen(vm) { navController.navigateUp() }
                }
                composable("projects") {
                    val vm = viewModel<SettingsViewModel>(factory = settingsVmFactory)
                    ProjectsScreen(vm) { navController.navigateUp() }
                }
                composable("counters") {
                    val vm = viewModel<com.lifeops.app.ui.screens.counters.CountersViewModel>(
                        factory = CountersViewModelFactory(
                            app.counterRepository, app.aspectRepository, app.weekRepository
                        )
                    )
                    CountersScreen(
                        vm,
                        onOpenCounter = { id -> navController.navigate("counter_detail/$id") },
                        onBack = { navController.navigateUp() }
                    )
                }
                composable("runbooks") {
                    val vm = viewModel<SettingsViewModel>(factory = settingsVmFactory)
                    RunbooksScreen(vm) { navController.navigateUp() }
                }
                composable("templates") {
                    val vm = viewModel<SettingsViewModel>(factory = settingsVmFactory)
                    TemplatesScreen(vm) { navController.navigateUp() }
                }
                composable("cost_resources") {
                    val vm = viewModel<SettingsViewModel>(factory = settingsVmFactory)
                    CostResourcesScreen(vm) { navController.navigateUp() }
                }
                composable("counter_detail/{counterId}") { backStackEntry ->
                    val counterId = backStackEntry.arguments?.getString("counterId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.counters.CounterDetailViewModel>(
                        key = "counter_detail_$counterId",
                        factory = CounterDetailViewModelFactory(counterId, app.counterRepository)
                    )
                    CounterDetailScreen(vm) { navController.navigateUp() }
                }
                composable("people") {
                    val vm = viewModel<com.lifeops.app.ui.screens.planning.PeopleViewModel>(
                        factory = PeopleViewModelFactory(app.personRepository)
                    )
                    PeopleScreen(
                        vm,
                        onOpenPerson = { id -> navController.navigate("person_detail/$id") },
                        onBack = { navController.navigateUp() }
                    )
                }
                composable("person_detail/{personId}") { backStackEntry ->
                    val personId = backStackEntry.arguments?.getString("personId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.planning.PersonDetailViewModel>(
                        key = "person_detail_$personId",
                        factory = PersonDetailViewModelFactory(
                            personId, app.personRepository, app.weekRepository, app.taskRepository
                        )
                    )
                    PersonDetailScreen(vm) { navController.navigateUp() }
                }
                composable("weather") {
                    val vm = viewModel<com.lifeops.app.ui.screens.weather.WeatherViewModel>(
                        factory = WeatherViewModelFactory(
                            app.weatherRepository, app.weekRepository, app.taskRepository,
                            app.personRepository, app.activityTemplateRepository
                        )
                    )
                    WeatherScreen(vm) { navController.navigateUp() }
                }
                composable("activities") {
                    val vm = viewModel<com.lifeops.app.ui.screens.planning.ActivitiesViewModel>(
                        factory = com.lifeops.app.ui.screens.planning.ActivitiesViewModelFactory(app.activityTemplateRepository)
                    )
                    com.lifeops.app.ui.screens.planning.ActivitiesScreen(vm) { navController.navigateUp() }
                }
            }

            // Game — the arcade run and its economy, reached via a hub. Resources is shared with
            // the History graph (registered there), reached by cross-graph route navigation.
            navigation(startDestination = "game_hub", route = Screen.Game.route) {
                composable("game_hub") {
                    com.lifeops.app.ui.screens.game.GameScreen(
                        onPlayRun = { navController.navigate("game_run") },
                        onOpenResources = { navController.navigate("resources") },
                        onOpenArtifacts = { navController.navigate("game_artifacts") }
                    )
                }
                composable("game_run") {
                    val vm = viewModel<com.lifeops.app.ui.screens.game.RunViewModel>(
                        factory = com.lifeops.app.ui.screens.game.RunViewModelFactory(app.gameResourceRepository)
                    )
                    com.lifeops.app.ui.screens.game.RunScreen(vm) { navController.navigateUp() }
                }
                composable("game_artifacts") {
                    com.lifeops.app.ui.screens.game.ArtifactCodexScreen { navController.navigateUp() }
                }
            }

            // History — backward-looking record screens, reached via a hub (Growth first).
            navigation(startDestination = "history_hub", route = Screen.History.route) {
                composable("history_hub") {
                    HistoryScreen(
                        onOpenGrowth = { navController.navigate("growth") },
                        onOpenReports = { navController.navigate("reports") },
                        onOpenResources = { navController.navigate("resources") }
                    )
                }
                composable("growth") {
                    val vm = viewModel<com.lifeops.app.ui.screens.growth.GrowthViewModel>(
                        factory = GrowthViewModelFactory(app.growthRepository)
                    )
                    GrowthScreen(vm, onBack = { navController.navigateUp() })
                }
                composable("reports") {
                    val vm = viewModel<com.lifeops.app.ui.screens.reports.ReportsViewModel>(
                        factory = ReportsViewModelFactory(
                            app.weekRepository, app.aspectRepository,
                            app.taskRepository, app.timeEntryRepository, app.costResourceRepository,
                            app.projectRepository
                        )
                    )
                    ReportsScreen(
                        vm,
                        onNavigateToProject = { id -> navController.navigate("project_detail/$id") },
                        onBack = { navController.navigateUp() }
                    )
                }
                composable("resources") {
                    val vm = viewModel<com.lifeops.app.ui.screens.resources.ResourcesViewModel>(
                        factory = ResourcesViewModelFactory(
                            app.gameResourceRepository, app.aspectRepository, app.taskRepository, app.weekRepository, app.timeEntryRepository
                        )
                    )
                    ResourcesScreen(vm, onBack = { navController.navigateUp() })
                }
                composable("project_detail/{projectId}") { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId") ?: return@composable
                    val vm = viewModel<com.lifeops.app.ui.screens.projectdetail.ProjectDetailViewModel>(
                        key = "project_detail_$projectId",
                        factory = ProjectDetailViewModelFactory(
                            projectId, app.projectRepository, app.taskRepository, app.weekRepository,
                            app.timeEntryRepository, app.taskNoteRepository, app.aspectRepository,
                            app.futureProjectRepository
                        )
                    )
                    ProjectDetailScreen(vm) { navController.navigateUp() }
                }
            }

            composable(Screen.Settings.route) {
                val vm = viewModel<SettingsViewModel>(factory = settingsVmFactory)
                SettingsScreen(vm)
            }
        }
    }
    } // CompositionLocalProvider
}
