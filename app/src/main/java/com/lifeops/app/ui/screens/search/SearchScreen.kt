@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.repository.SearchResult
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

/**
 * Global search across tasks, notes, projects, people, counters, books, recipes, future projects,
 * runbooks, templates, activities, foods, and weather locations. Tapping a result opens its detail
 * when one exists (via [onNavigate]); dashboard-level hits jump to their hub screen.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onNavigate: (route: String) -> Unit,
    onBack: () -> Unit
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                label = { Text("Search everything") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardActions = KeyboardActions()
            )

            when {
                query.trim().length < 2 -> Hint("Type at least 2 characters to search tasks, notes, planning items, collections, foods, and weather locations.")
                results.isEmpty() -> Hint("No matches for \"${query.trim()}\".")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    val grouped = results.groupBy { it.category }
                    grouped.forEach { (category, items) ->
                        item(key = "h_${category.name}") {
                            Text(
                                category.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items.forEach { result ->
                            item(key = "${category.name}_${result.id}") {
                                ResultRow(result, onNavigate)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, onNavigate: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (result.route != null) Modifier.clickable { onNavigate(result.route) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(result.title, style = MaterialTheme.typography.bodyLarge)
        result.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(16.dp)
    )
}
