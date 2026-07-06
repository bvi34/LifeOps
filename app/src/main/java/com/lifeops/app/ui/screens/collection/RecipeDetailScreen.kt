@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon

@Composable
fun RecipeDetailScreen(viewModel: RecipeDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recipe = state.recipe

    Scaffold(
        topBar = {
            AppHeader(navigationIcon = { BackNavIcon(onBack) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddIngredientDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "Add ingredient")
            }
        }
    ) { padding ->
        if (recipe == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(recipe.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${recipe.servings} serving(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = { viewModel.showEditDialog() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit recipe")
                    }
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete recipe")
                    }
                }
            }
            state.nutrition?.let { nutrition ->
                item { NutritionCard(nutrition.perServing) }
            }
            item {
                Text("Ingredients", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (state.ingredients.isEmpty()) {
                item {
                    Text(
                        "No ingredients yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                items(state.ingredients, key = { it.ingredient.id }) { row ->
                    IngredientRowCard(row, onRemove = { viewModel.removeIngredient(row.ingredient.id) })
                }
            }
        }
    }

    if (state.showEditDialog && recipe != null) {
        EditRecipeDialog(
            initialName = recipe.name,
            initialServings = recipe.servings,
            onDismiss = { viewModel.hideEditDialog() },
            onConfirm = { name, servings -> viewModel.rename(name, servings) }
        )
    }

    if (state.showAddIngredientDialog) {
        AddIngredientDialog(
            searchQuery = state.searchQuery,
            searchResults = state.searchResults,
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            onDismiss = { viewModel.hideAddIngredientDialog() },
            onAdd = { foodItemId, quantity, unit -> viewModel.addIngredient(foodItemId, quantity, unit) }
        )
    }
}

@Composable
private fun NutritionCard(perServing: NutritionTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Per serving", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text("${perServing.calories.toInt()} kcal", style = MaterialTheme.typography.bodySmall)
            Text(
                "C ${perServing.carbsG.toInt()}g · P ${perServing.proteinG.toInt()}g · F ${perServing.fatG.toInt()}g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun IngredientRowCard(row: IngredientRow, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.foodName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${row.ingredient.quantity} ${row.ingredient.unit.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ingredient")
            }
        }
    }
}

@Composable
private fun EditRecipeDialog(
    initialName: String,
    initialServings: Double,
    onDismiss: () -> Unit,
    onConfirm: (name: String, servings: Double) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var servingsText by rememberSaveable { mutableStateOf(initialServings.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it },
                    label = { Text("Servings") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), servingsText.toDoubleOrNull() ?: 1.0) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddIngredientDialog(
    searchQuery: String,
    searchResults: List<FoodItem>,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (foodItemId: String, quantity: Double, unit: IngredientUnit) -> Unit
) {
    var quantityText by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(IngredientUnit.SERVING) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ingredient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    label = { Text("Search foods") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity") },
                        modifier = Modifier.weight(1f)
                    )
                    IngredientUnit.entries.forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u.name.lowercase()) }
                        )
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(searchResults, key = { it.id }) { food ->
                        TextButton(
                            onClick = {
                                val qty = quantityText.toDoubleOrNull() ?: 1.0
                                onAdd(food.id, qty, unit)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(food.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
