@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weeklymenu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.util.DateUtil
import java.time.LocalDate

@Composable
fun WeeklyMenuScreen(viewModel: WeeklyMenuViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader() },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "Add meal")
            }
        }
    ) { padding ->
        if (state.floatingMeals.isEmpty() && state.pinnedMeals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No meals planned for this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.pinnedMeals.isNotEmpty()) {
                    item {
                        Text(
                            "Pinned to a day",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.pinnedMeals, key = { it.id }) { item ->
                        MenuItemRow(
                            item = item,
                            weekStartDate = state.weekStartDate,
                            isAssigning = state.assigningItemId == item.id,
                            onStartAssign = { viewModel.startAssigning(item.id) },
                            onCancelAssign = { viewModel.cancelAssigning() },
                            onAssign = { date -> viewModel.assignToDate(item.id, date) },
                            onUnassign = { viewModel.unassign(item.id) },
                            onDelete = { viewModel.delete(item.id) }
                        )
                    }
                }
                if (state.floatingMeals.isNotEmpty()) {
                    item {
                        Text(
                            "Floating",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(state.floatingMeals, key = { it.id }) { item ->
                        MenuItemRow(
                            item = item,
                            weekStartDate = state.weekStartDate,
                            isAssigning = state.assigningItemId == item.id,
                            onStartAssign = { viewModel.startAssigning(item.id) },
                            onCancelAssign = { viewModel.cancelAssigning() },
                            onAssign = { date -> viewModel.assignToDate(item.id, date) },
                            onUnassign = { viewModel.unassign(item.id) },
                            onDelete = { viewModel.delete(item.id) }
                        )
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddMealDialog(
            recipes = state.recipes,
            onDismiss = { viewModel.hideAddDialog() },
            onConfirm = { name, recipeId, servings -> viewModel.addMeal(name, recipeId, servings, null) }
        )
    }
}

@Composable
private fun MenuItemRow(
    item: WeeklyMenuItem,
    weekStartDate: String,
    isAssigning: Boolean,
    onStartAssign: () -> Unit,
    onCancelAssign: () -> Unit,
    onAssign: (String) -> Unit,
    onUnassign: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.mealName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    val dayLabel = item.assignedDate?.let { DateUtil.formatDate(it) }
                    Text(
                        if (dayLabel != null) "$dayLabel · ${item.plannedServings} serving(s)" else "${item.plannedServings} serving(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (item.assignedDate == null) {
                    IconButton(onClick = onStartAssign) {
                        Icon(Icons.Default.Event, contentDescription = "Assign to a day")
                    }
                } else {
                    TextButton(onClick = onUnassign) { Text("Unassign") }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete meal")
                }
            }
            if (isAssigning) {
                val weekDates = remember(weekStartDate) {
                    val start = LocalDate.parse(weekStartDate)
                    (0..6).map { start.plusDays(it.toLong()).toString() }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    weekDates.forEach { date ->
                        AssistChip(
                            onClick = { onAssign(date) },
                            label = { Text(DateUtil.formatDate(date)) }
                        )
                    }
                    IconButton(onClick = onCancelAssign) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMealDialog(
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, recipeId: String?, servings: Double) -> Unit
) {
    var mealName by rememberSaveable { mutableStateOf("") }
    var selectedRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var servingsText by rememberSaveable { mutableStateOf("1") }
    var recipeMenuExpanded by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = mealName,
                    onValueChange = { mealName = it },
                    label = { Text("Meal name") },
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = recipeMenuExpanded,
                    onExpandedChange = { recipeMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = recipes.firstOrNull { it.id == selectedRecipeId }?.name ?: "No recipe",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Recipe (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recipeMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = recipeMenuExpanded,
                        onDismissRequest = { recipeMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No recipe") },
                            onClick = { selectedRecipeId = null; recipeMenuExpanded = false }
                        )
                        recipes.forEach { recipe ->
                            DropdownMenuItem(
                                text = { Text(recipe.name) },
                                onClick = { selectedRecipeId = recipe.id; recipeMenuExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it },
                    label = { Text("Planned servings") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val servings = servingsText.toDoubleOrNull() ?: 1.0
                    if (mealName.isNotBlank()) onConfirm(mealName.trim(), selectedRecipeId, servings)
                },
                enabled = mealName.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
