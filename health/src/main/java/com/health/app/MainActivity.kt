package com.health.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.health.app.ui.information.InformationScreen
import com.health.app.ui.information.InformationViewModel
import com.health.app.ui.meds.MedsScreen
import com.health.app.ui.meds.MedsViewModel
import com.health.app.ui.record.RecordScreen
import com.health.app.ui.record.RecordViewModel
import com.health.app.ui.theme.HealthTheme
import com.health.app.ui.today.TodayScreen
import com.health.app.ui.today.TodayViewModel
import com.health.app.ui.vitals.VitalsScreen
import com.health.app.ui.vitals.VitalsViewModel

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    object Today : Dest("today", "Today", Icons.Default.Today)
    object Vitals : Dest("vitals", "Vitals", Icons.Default.MonitorHeart)
    object Meds : Dest("meds", "Meds", Icons.Default.MedicalServices)
    object Information : Dest("information", "Information", Icons.Default.Info)
    object Record : Dest("record", "Record", Icons.Default.Assignment)
    object Coverage : Dest("coverage", "Care", Icons.Default.Badge)
}

private val navItems =
    listOf(Dest.Today, Dest.Vitals, Dest.Meds, Dest.Information, Dest.Record, Dest.Coverage)

/**
 * Health's single entry point. A tabbed shell — Today, Vitals, Meds, Information, Record, Care — over
 * the one [HealthApp] runtime.
 *
 * The tabs divide by the *kind of question* they answer, not by data type. Today, Vitals and Meds are
 * about things that **happened**; Information is who a person is and what is normal for them, with
 * their illnesses under it; Record is what is standing-true about them — allergies, conditions,
 * vaccinations and the paperwork; Care is who pays and who provides.
 *
 * **There is no People tab, deliberately.** Health does not own the household — the People app does,
 * and Health is a peer on its sync seam (see `HealthSyncService`). A second place to add, rename or
 * remove somebody would be a second answer to "who lives here", and the whole point of the seam is
 * that there is one. Switching between people is the profile bar's job on every tab, and the bar's
 * last chip opens People for everything else.
 *
 * Every tab shows the same profile bar and reads the same selected person, so
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
                val context = LocalContext.current

                /**
                 * Open the People app — the household's own screen, in the same process.
                 *
                 * The sandbox launches its hosted apps exactly this way (see `SandboxActivity`), and
                 * `:health` already depends on `:people` for the sync contract, so this adds no
                 * dependency edge. It is what the profile bar's chip and every empty state point at
                 * now that Health has no household screen of its own.
                 */
                fun openPeople() {
                    runCatching {
                        context.startActivity(Intent(context, com.people.app.MainActivity::class.java))
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
                            TodayScreen(vm, onOpenPeople = { openPeople() })
                        }
                        composable(Dest.Vitals.route) {
                            val vm: VitalsViewModel = viewModel(factory = VitalsViewModel.Factory(app.repository))
                            VitalsScreen(vm, onOpenPeople = { openPeople() })
                        }
                        composable(Dest.Meds.route) {
                            val vm: MedsViewModel = viewModel(
                                factory = MedsViewModel.Factory(app.repository, app.drugLookup)
                            )
                            MedsScreen(vm, onOpenPeople = { openPeople() })
                        }
                        composable(Dest.Information.route) {
                            val vm: InformationViewModel = viewModel(factory = InformationViewModel.Factory(app.repository))
                            InformationScreen(vm, onOpenPeople = { openPeople() })
                        }
                        composable(Dest.Record.route) {
                            val vm: RecordViewModel = viewModel(
                                factory = RecordViewModel.Factory(app.repository, app.documents)
                            )
                            RecordScreen(vm, onOpenPeople = { openPeople() })
                        }
                        composable(Dest.Coverage.route) {
                            val vm: CoverageViewModel = viewModel(
                                factory = CoverageViewModel.Factory(
                                    app.repository,
                                    app.providerDirectory,
                                    app.cardImages
                                )
                            )
                            CoverageScreen(vm, onOpenPeople = { openPeople() })
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
