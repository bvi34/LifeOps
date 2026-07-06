@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifeops.app.ui.components.AppHeader

enum class CollectionTab(val label: String) {
    RECIPES("Recipes"),
    BOOKS("Books"),
    PROJECTS("Projects")
}

/** Collection: a reference library independent of any given week — recipes to cook from,
 *  books to read, and future project ideas jotted down for later. */
@Composable
fun CollectionScreen(
    recipeViewModel: RecipeViewModel,
    bookViewModel: BookViewModel,
    futureProjectViewModel: FutureProjectViewModel,
    onOpenRecipe: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenFutureProject: (String) -> Unit
) {
    var selectedTab by rememberSaveable { mutableStateOf(CollectionTab.RECIPES) }

    Scaffold(topBar = { AppHeader() }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                CollectionTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CollectionTab.entries.size)
                    ) { Text(tab.label) }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    CollectionTab.RECIPES -> RecipesScreen(recipeViewModel, onOpenRecipe)
                    CollectionTab.BOOKS -> BooksScreen(bookViewModel, onOpenBook)
                    CollectionTab.PROJECTS -> FutureProjectsScreen(futureProjectViewModel, onOpenFutureProject)
                }
            }
        }
    }
}
