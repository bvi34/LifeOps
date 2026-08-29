package com.people.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.people.app.ui.detail.PersonDetailScreen
import com.people.app.ui.detail.PersonDetailViewModel
import com.people.app.ui.roster.RosterScreen
import com.people.app.ui.roster.RosterViewModel
import com.people.app.ui.theme.PeopleTheme

/**
 * People's single entry point: the roster, and one person at a time.
 *
 * Bringing the app to the foreground runs a sync round. That is deliberate rather than lazy — the
 * directory's whole job is to agree with the other apps, and the cheapest moment to reconcile is the
 * moment somebody is about to look at it. The round is idempotent, so doing it on every open costs a
 * file read when there is nothing to do.
 *
 * It hangs off the lifecycle rather than off first composition because the seven suite apps share one
 * process: walking from People to Health and back does not recreate this activity, and a round that
 * only ran when the roster was first composed would miss exactly the edit the user just made
 * somewhere else.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = PeopleApp.get(this)

        setContent {
            PeopleTheme {
                val nav = rememberNavController()

                Scaffold(topBar = { PeopleTopBar() }) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = "roster",
                        modifier = Modifier.padding(padding)
                    ) {
                        composable("roster") {
                            val vm: RosterViewModel = viewModel(
                                factory = RosterViewModel.Factory(app.repository, app.syncService, app.peers)
                            )
                            SyncOnStart(vm)
                            RosterScreen(vm, onOpenPerson = { nav.navigate("person/${it.id}") })
                        }
                        composable(
                            route = "person/{personId}",
                            arguments = listOf(navArgument("personId") { type = NavType.StringType })
                        ) { entry ->
                            val personId = entry.arguments?.getString("personId").orEmpty()
                            val vm: PersonDetailViewModel = viewModel(
                                factory = PersonDetailViewModel.Factory(
                                    app.repository,
                                    app.syncService,
                                    app.peers,
                                    personId
                                )
                            )
                            PersonDetailScreen(vm, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reconcile whenever the roster becomes visible.
 *
 * `ON_START` also fires the moment the observer is registered on an already-started lifecycle, so
 * this covers the first composition as well as every later return to the foreground — one trigger
 * rather than a `LaunchedEffect` for the first case and something else for all the others.
 */
@Composable
private fun SyncOnStart(vm: RosterViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.sync()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleTopBar() {
    TopAppBar(title = { Text("People") })
}
