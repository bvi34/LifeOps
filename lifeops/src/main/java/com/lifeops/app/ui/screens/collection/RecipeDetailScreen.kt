@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
                item { NutritionCard(nutrition.perServing, state.ingredients.count { it.gap != null }, state.ingredients.size) }
            }
            recipe.sourceUrl?.let { url ->
                item { SourceLink(url) }
            }
            item { MethodCard(recipe.instructions) }
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
            initialInstructions = recipe.instructions.orEmpty(),
            initialSourceUrl = recipe.sourceUrl.orEmpty(),
            onDismiss = { viewModel.hideEditDialog() },
            onConfirm = { name, servings, instructions, sourceUrl ->
                viewModel.edit(name, servings, instructions, sourceUrl)
            }
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

/** Per-serving macros, honest about what they leave out: [uncounted] of [total] ingredient lines
 *  contribute nothing (a web import with no published nutrition, a deleted food, an unconvertible
 *  gram quantity), so the number is a floor and says so rather than reading as a real total. */
@Composable
private fun NutritionCard(perServing: NutritionTotals, uncounted: Int, total: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (uncounted > 0) "Per serving (at least)" else "Per serving",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text("${perServing.calories.toInt()} kcal", style = MaterialTheme.typography.bodySmall)
            Text(
                "C ${perServing.carbsG.toInt()}g · P ${perServing.proteinG.toInt()}g · F ${perServing.fatG.toInt()}g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (uncounted > 0) {
                Text(
                    "$uncounted of $total ingredients aren't counted here — add their macros for a real total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** The method. Free text, one step per line — this is the half that makes a recipe cookable
 *  rather than just an ingredient vector. */
@Composable
private fun MethodCard(instructions: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Method", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (instructions.isNullOrBlank()) {
                Text(
                    "No method recorded. Tap the pencil to write one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                instructions.lines().filter { it.isNotBlank() }.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. ${step.trim()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** Where an imported recipe came from — tapping it reopens the original page. */
@Composable
private fun SourceLink(url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        url,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } }
    )
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
                row.gap?.let { gap ->
                    Text(
                        gap.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
    initialInstructions: String,
    initialSourceUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, servings: Double, instructions: String, sourceUrl: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var servingsText by rememberSaveable { mutableStateOf(initialServings.toString()) }
    var instructions by rememberSaveable { mutableStateOf(initialInstructions) }
    var sourceUrl by rememberSaveable { mutableStateOf(initialSourceUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit recipe") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
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
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Method — one step per line") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    label = { Text("Source link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(),
                        servingsText.toDoubleOrNull() ?: 1.0,
                        instructions.trim(),
                        sourceUrl.trim()
                    )
                },
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
