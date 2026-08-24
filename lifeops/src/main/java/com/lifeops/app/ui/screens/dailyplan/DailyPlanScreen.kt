@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.dailyplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

@Composable
fun DailyPlanScreen(viewModel: DailyPlanViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(windowInsets = WindowInsets(0, 0, 0, 0)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "Add food")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
            DayChipRow(
                weekStartDate = state.weekStartDate,
                selectedDate = state.selectedDate,
                onSelectDate = { viewModel.selectDate(it) }
            )
            TotalsCard(planned = state.plannedTotals, confirmed = state.confirmedTotals)
            OutlinedButton(
                onClick = { viewModel.showPlanDialog() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("Plan a meal from a recipe") }
            if (state.entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Nothing logged or planned for this day yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        LogEntryRow(
                            entry = entry,
                            isAdjusting = state.adjustingEntryId == entry.id,
                            onConfirm = { viewModel.confirmEntry(entry.id) },
                            onStartAdjust = { viewModel.startAdjusting(entry.id) },
                            onCancelAdjust = { viewModel.cancelAdjusting() },
                            onAdjust = { qty, unit -> viewModel.adjustEntry(entry.id, qty, unit) },
                            onUnplan = { entry.weeklyMenuItemId?.let { viewModel.unplan(it) } }
                        )
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddFoodDialog(
            searchQuery = state.searchQuery,
            searchResults = state.searchResults,
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            onDismiss = { viewModel.hideAddDialog() },
            onAddFoodItem = { foodItemId, quantity, unit -> viewModel.addFoodItem(foodItemId, quantity, unit) },
            onAddAdHoc = { name, qty, unit, cal, carbs, protein, fat ->
                viewModel.addAdHoc(name, qty, unit, cal, carbs, protein, fat)
            }
        )
    }

    if (state.showPlanDialog) {
        PlanMealDialog(
            recipes = state.recipes,
            onDismiss = { viewModel.hidePlanDialog() },
            onPlan = { recipeId, servings, mealType -> viewModel.planMeal(recipeId, servings, mealType) }
        )
    }
}

@Composable
private fun DayChipRow(weekStartDate: String, selectedDate: String, onSelectDate: (String) -> Unit) {
    val weekDates = remember(weekStartDate) {
        val start = LocalDate.parse(weekStartDate)
        (0..6).map { start.plusDays(it.toLong()).toString() }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        weekDates.forEach { date ->
            FilterChip(
                selected = date == selectedDate,
                onClick = { onSelectDate(date) },
                label = { Text(DateUtil.formatDate(date)) }
            )
        }
    }
}

@Composable
private fun TotalsCard(planned: NutritionTotals, confirmed: NutritionTotals) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Daily totals", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TotalsColumn(label = "Planned", totals = planned, modifier = Modifier.weight(1f))
                TotalsColumn(label = "Confirmed", totals = confirmed, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TotalsColumn(label: String, totals: NutritionTotals, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Text("${totals.calories.toInt()} kcal", style = MaterialTheme.typography.bodySmall)
        Text(
            "C ${totals.carbsG.toInt()}g · P ${totals.proteinG.toInt()}g · F ${totals.fatG.toInt()}g",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LogEntryRow(
    entry: FoodLogEntry,
    isAdjusting: Boolean,
    onConfirm: () -> Unit,
    onStartAdjust: () -> Unit,
    onCancelAdjust: () -> Unit,
    onAdjust: (Double, IngredientUnit) -> Unit,
    onUnplan: () -> Unit
) {
    // A planned meal is a suggestion until it's confirmed: it can be dropped outright, where an
    // ad-hoc entry (already eaten) can only be adjusted.
    val isPlanned = !entry.confirmed && entry.source == FoodLogSource.PLANNED && entry.weeklyMenuItemId != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(
                        "${entry.quantity} ${entry.unit.name.lowercase()} · ${entry.calories.toInt()} kcal" +
                            if (isPlanned) " · planned" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (!entry.confirmed) {
                    IconButton(onClick = onConfirm) {
                        Icon(Icons.Default.Check, contentDescription = "Confirm")
                    }
                }
                if (isPlanned) {
                    IconButton(onClick = onUnplan) {
                        Icon(Icons.Default.Close, contentDescription = "Remove planned meal")
                    }
                }
                TextButton(onClick = onStartAdjust) { Text("Adjust") }
            }
            if (isAdjusting) {
                var quantityText by rememberSaveable(entry.id) { mutableStateOf(entry.quantity.toString()) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (${entry.unit.name.lowercase()})") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val qty = quantityText.toDoubleOrNull() ?: entry.quantity
                        onAdjust(qty, entry.unit)
                    }) { Text("Save") }
                    TextButton(onClick = onCancelAdjust) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun AddFoodDialog(
    searchQuery: String,
    searchResults: List<FoodItem>,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddFoodItem: (foodItemId: String, quantity: Double, unit: IngredientUnit) -> Unit,
    onAddAdHoc: (name: String, quantity: Double, unit: IngredientUnit, calories: Double, carbsG: Double, proteinG: Double, fatG: Double) -> Unit
) {
    var adHocMode by rememberSaveable { mutableStateOf(false) }
    var quantityText by rememberSaveable { mutableStateOf("1") }
    var adHocName by rememberSaveable { mutableStateOf("") }
    var adHocCalories by rememberSaveable { mutableStateOf("") }
    var adHocCarbs by rememberSaveable { mutableStateOf("") }
    var adHocProtein by rememberSaveable { mutableStateOf("") }
    var adHocFat by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adHocMode) "Add ad-hoc food" else "Add food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!adHocMode) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        label = { Text("Search foods") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (servings)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(searchResults, key = { it.id }) { food ->
                            TextButton(
                                onClick = {
                                    val qty = quantityText.toDoubleOrNull() ?: 1.0
                                    onAddFoodItem(food.id, qty, IngredientUnit.SERVING)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(food.name) }
                        }
                    }
                    TextButton(onClick = { adHocMode = true }) { Text("Can't find it? Add ad-hoc") }
                } else {
                    OutlinedTextField(
                        value = adHocName,
                        onValueChange = { adHocName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (servings)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adHocCalories,
                        onValueChange = { adHocCalories = it },
                        label = { Text("Calories") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adHocCarbs,
                        onValueChange = { adHocCarbs = it },
                        label = { Text("Carbs (g)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adHocProtein,
                        onValueChange = { adHocProtein = it },
                        label = { Text("Protein (g)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adHocFat,
                        onValueChange = { adHocFat = it },
                        label = { Text("Fat (g)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { adHocMode = false }) { Text("Back to search") }
                }
            }
        },
        confirmButton = {
            if (adHocMode) {
                TextButton(
                    onClick = {
                        onAddAdHoc(
                            adHocName.trim(),
                            quantityText.toDoubleOrNull() ?: 1.0,
                            IngredientUnit.SERVING,
                            adHocCalories.toDoubleOrNull() ?: 0.0,
                            adHocCarbs.toDoubleOrNull() ?: 0.0,
                            adHocProtein.toDoubleOrNull() ?: 0.0,
                            adHocFat.toDoubleOrNull() ?: 0.0
                        )
                    },
                    enabled = adHocName.isNotBlank()
                ) { Text("Add") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Plans a recipe onto the selected day. The recipe book is the only source here — a meal you can't
 * name from the book is just an ad-hoc log, which the + button already covers.
 */
@Composable
private fun PlanMealDialog(
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onPlan: (recipeId: String, servings: Double, mealType: String?) -> Unit
) {
    var selectedRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var servingsText by rememberSaveable { mutableStateOf("1") }
    var mealType by rememberSaveable { mutableStateOf(MEAL_TYPES.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plan a meal") },
        text = {
            if (recipes.isEmpty()) {
                Text(
                    "No recipes yet — add one in Collection first.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MEAL_TYPES.forEach { type ->
                            FilterChip(
                                selected = mealType == type,
                                onClick = { mealType = type },
                                label = { Text(type.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = servingsText,
                        onValueChange = { servingsText = it },
                        label = { Text("Servings") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(recipes, key = { it.id }) { recipe ->
                            val selected = recipe.id == selectedRecipeId
                            TextButton(
                                onClick = { selectedRecipeId = recipe.id },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (selected) "● ${recipe.name}" else recipe.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedRecipeId?.let {
                        onPlan(it, servingsText.toDoubleOrNull() ?: 1.0, mealType)
                    }
                },
                enabled = selectedRecipeId != null
            ) { Text("Plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val MEAL_TYPES = listOf("breakfast", "lunch", "snack", "dinner")
