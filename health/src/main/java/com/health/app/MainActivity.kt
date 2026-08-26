package com.health.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Today
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
import com.health.app.ui.coverage.CoverageScreen
import com.health.app.ui.coverage.CoverageViewModel
import com.health.app.ui.episodes.EpisodesScreen
import com.health.app.ui.episodes.EpisodesViewModel
import com.health.app.ui.meds.MedsScreen
import com.health.app.ui.meds.MedsViewModel
import com.health.app.ui.people.PeopleScreen
import com.health.app.ui.people.PeopleViewModel
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayViewModel
import com.health.app.ui.vitals.VitalsScreen
import com.health.app.ui.vitals.VitalsViewModel

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    object Today : Dest("today", "Today", Icons.Default.Today)
    object Vitals : Dest("vitals", "Vitals", Icons.Default.MonitorHeart)
    object Meds : Dest("meds", "Meds", Icons.Default.MedicalServices)
    object Episodes : Dest("episodes", "Illness", Icons.Default.Sick)
    object Coverage : Dest("coverage", "Care", Icons.Default.Badge)
    object People : Dest("people", "People", Icons.Default.Groups)
}

private val navItems =
    listOf(Dest.Today, Dest.Vitals, Dest.Meds, Dest.Episodes, Dest.Coverage, Dest.People)

/**
 * Health's single entry point. A tabbed shell — Today, Vitals, Meds, Illness, Care, People — over the
 * one [HealthApp] runtime.
 *
 * Every tab except People shows the same profile bar and reads the same selected person, so
 * switching who you're looking at is one tap from anywhere and is never ambiguous. That is the whole
 * design constraint of a household health app: the readings are worthless, and worse than worthless,
 * if they end up under the wrong name.
 */
class MainActivity : ComponentActivity() {

    /**
     * Reconcile with the household directory every time Health comes to the foreground.
     *
     * On `onStart` rather than on first composition because all six suite apps share one process:
     * walking from Health to People and back does not recreate this activity, so a round tied to
     * composition would run once and then never again for the rest of the session — missing exactly
     * the edit the user just made next door. The round is idempotent and best-effort (a missing or
     * half-written envelope must never block the screen), and it is what brings a person's birth
     * date over from People, which is what the age-aware fever rules need.
     */
    override fun onStart() {
        super.onStart()
        HealthApp.get(this).syncPeople()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = HealthApp.get(this)

        setContent {
            HealthTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination
                // Set when another tab's "Add person" chip is used, so People opens with the form up.
                var openAddProfile by remember { mutableStateOf(false) }

                fun goAddProfile() {
                    openAddProfile = true
                    nav.navigate(Dest.People.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                Scaffold(
                    topBar = { TopAppBarTitle(current?.route) },
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
                        startDestination = Dest.Today.route,
                        modifier = Modifier.padding(padding)
                    ) {
                        composable(Dest.Today.route) {
                            val vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory(app.repository))
                            TodayScreen(vm, onAddProfile = { goAddProfile() })
                        }
                        composable(Dest.Vitals.route) {
                            val vm: VitalsViewModel = viewModel(factory = VitalsViewModel.Factory(app.repository))
                            VitalsScreen(vm, onAddProfile = { goAddProfile() })
                        }
                        composable(Dest.Meds.route) {
                            val vm: MedsViewModel = viewModel(
                                factory = MedsViewModel.Factory(app.repository, app.drugLookup)
                            )
                            MedsScreen(vm, onAddProfile = { goAddProfile() })
                        }
                        composable(Dest.Episodes.route) {
                            val vm: EpisodesViewModel = viewModel(factory = EpisodesViewModel.Factory(app.repository))
                            EpisodesScreen(vm, onAddProfile = { goAddProfile() })
                        }
                        composable(Dest.Coverage.route) {
                            val vm: CoverageViewModel = viewModel(
                                factory = CoverageViewModel.Factory(
                                    app.repository,
                                    app.providerDirectory,
                                    app.cardImages
                                )
                            )
                            CoverageScreen(vm, onAddProfile = { goAddProfile() })
                        }
                        composable(Dest.People.route) {
                            val vm: PeopleViewModel = viewModel(factory = PeopleViewModel.Factory(app.repository))
                            PeopleScreen(
                                vm = vm,
                                showAddInitially = openAddProfile,
                                onAddHandled = { openAddProfile = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarTitle(route: String?) {
    val title = navItems.firstOrNull { it.route == route }?.label ?: "Health"
    TopAppBar(title = { Text(if (title == "Today") "Health" else title) })
}
