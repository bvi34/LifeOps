package com.logistics.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.logistics.app.ui.grocery.GroceryScreen
import com.logistics.app.ui.grocery.GroceryViewModel
import com.logistics.app.ui.history.HistoryScreen
import com.logistics.app.ui.history.HistoryViewModel
import com.logistics.app.ui.importflow.ImportScreen
import com.logistics.app.ui.importflow.ImportViewModel
import com.logistics.app.ui.meal.LogMealScreen
import com.logistics.app.ui.meal.LogMealViewModel
import com.logistics.app.ui.pantry.PantryScreen
import com.logistics.app.ui.pantry.PantryViewModel
import com.logistics.app.ui.recipe.RecipeScreen
import com.logistics.app.ui.recipe.RecipeViewModel
import com.logistics.app.ui.theme.LogisticsTheme

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    object Pantry : Dest("pantry", "Pantry", Icons.Default.Inventory2)
    object Grocery : Dest("grocery", "Grocery", Icons.Default.ShoppingCart)
    object Meal : Dest("meal", "Log meal", Icons.Default.Restaurant)
    object History : Dest("history", "History", Icons.Default.History)
    object Recipes : Dest("recipes", "Recipes", Icons.Default.MenuBook)
    object Import : Dest("import", "Import", Icons.Default.ReceiptLong)
}

private val navItems = listOf(Dest.Pantry, Dest.Grocery, Dest.Meal, Dest.History, Dest.Recipes, Dest.Import)

/**
 * Logistics' single entry point. A tabbed shell — Pantry, Grocery list, Log meal, History, Recipes,
 * Import — over the one [LogisticsApp] runtime. It also accepts a Walmart PDF shared from another
 * app (→ Import) and a shared recipe link or order text (→ Recipes / Import).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = LogisticsApp.get(this)

        // Interpret how we were opened. A Walmart order PDF arrives by *sharing* it to Logistics;
        // plainly opening a PDF goes to Citation, which owns reading. An explicit ACTION_VIEW is
        // still honoured for anything that routes one here directly.
        val importPdfUri: Uri? = when {
            intent?.action == Intent.ACTION_SEND && intent.type == "application/pdf" ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            intent?.action == Intent.ACTION_VIEW && intent.type == "application/pdf" -> intent.data
            else -> null
        }
        val sharedText: String? = intent
            ?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        val sharedUrl = sharedText?.takeIf { it.trim().startsWith("http", ignoreCase = true) }
        val orderText = sharedText?.takeUnless { it.trim().startsWith("http", ignoreCase = true) }
        val startRoute = when {
            importPdfUri != null || orderText != null -> Dest.Import.route
            sharedUrl != null -> Dest.Recipes.route
            else -> Dest.Pantry.route
        }

        setContent {
            LogisticsTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination

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
                        startDestination = startRoute,
                        modifier = Modifier.padding(padding)
                    ) {
                        composable(Dest.Pantry.route) {
                            val vm: PantryViewModel = viewModel(factory = PantryViewModel.Factory(app.pantryRepository))
                            PantryScreen(vm)
                        }
                        composable(Dest.Grocery.route) {
                            val vm: GroceryViewModel = viewModel(factory = GroceryViewModel.Factory(app.pantryRepository, app.catalog))
                            GroceryScreen(vm)
                        }
                        composable(Dest.Import.route) {
                            val vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory(app.pantryRepository))
                            ImportScreen(vm, initialPdf = importPdfUri, initialText = orderText)
                        }
                        composable(Dest.Meal.route) {
                            val vm: LogMealViewModel = viewModel(factory = LogMealViewModel.Factory(app.pantryRepository, app.catalog))
                            LogMealScreen(vm)
                        }
                        composable(Dest.History.route) {
                            val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(app.pantryRepository))
                            HistoryScreen(vm)
                        }
                        composable(Dest.Recipes.route) {
                            val vm: RecipeViewModel = viewModel(factory = RecipeViewModel.Factory(app.catalog))
                            RecipeScreen(vm, initialUrl = sharedUrl)
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
    val title = navItems.firstOrNull { it.route == route }?.label ?: "Logistics"
    TopAppBar(title = { Text(title) })
}
