package com.logistics.app.ui.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Recipe
import com.logistics.app.data.model.GroceryItem
import com.logistics.app.data.model.GrocerySource
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.data.repository.PantryRepository
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroceryViewModel(
    private val repo: PantryRepository,
    private val catalog: LifeOpsCatalog
) : ViewModel() {

    val items: StateFlow<List<GroceryItem>> =
        repo.observeGrocery().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recipes: StateFlow<List<Recipe>> =
        catalog.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var status by mutableStateOf<String?>(null)
        private set

    fun clearStatus() { status = null }

    fun add(name: String, qty: Double, unit: String, category: String?) = viewModelScope.launch {
        repo.addGroceryItem(name, qty, unit, category, source = GrocerySource.MANUAL)
    }

    fun toggle(item: GroceryItem) = viewModelScope.launch { repo.setGroceryChecked(item.id, !item.checked) }
    fun setQuantity(item: GroceryItem, qty: Double) = viewModelScope.launch { repo.setGroceryQuantity(item.id, qty) }
    fun remove(item: GroceryItem) = viewModelScope.launch { repo.removeGroceryItem(item.id) }

    fun addLowStock() = viewModelScope.launch {
        val n = repo.addLowStockToGrocery()
        status = if (n > 0) "Added $n low-stock item(s) to your list." else "Nothing's running low right now."
    }

    fun addRecipe(recipe: Recipe) = viewModelScope.launch {
        val n = repo.addRecipeMissingToGrocery(recipe.id)
        status = if (n > 0) "Added $n missing ingredient(s) for \"${recipe.name}\"."
                 else "You already have everything for \"${recipe.name}\"."
    }

    fun purchaseChecked() = viewModelScope.launch {
        val n = repo.purchaseCheckedIntoPantry()
        status = if (n > 0) "Shelved $n item(s) into your pantry." else "Check off what you bought first."
    }

    fun clearChecked() = viewModelScope.launch { repo.clearCheckedGrocery() }

    class Factory(
        private val repo: PantryRepository,
        private val catalog: LifeOpsCatalog
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GroceryViewModel(repo, catalog) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryScreen(vm: GroceryViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var showRecipePick by remember { mutableStateOf(false) }

    LaunchedEffect(vm.status) {
        vm.status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
    }

    val checkedCount = items.count { it.checked }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add item") }
            )
        },
        bottomBar = {
            if (checkedCount > 0) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { vm.clearChecked() }) { Text("Clear") }
                        Button(onClick = { vm.purchaseChecked() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add $checkedCount to pantry")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Build-the-list actions.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { vm.addLowStock() },
                    label = { Text("Restock low") },
                    leadingIcon = { Icon(Icons.Default.TrendingDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                AssistChip(
                    onClick = { showRecipePick = true },
                    enabled = recipes.isNotEmpty(),
                    label = { Text("From recipe") },
                    leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
            HorizontalDivider()

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Your grocery list is empty.\n\nAdd items by hand, pull in what's running low, or gather a recipe's missing ingredients — then check things off as you shop and add them to your pantry in one tap.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                val grouped = items.groupBy { it.category ?: "Other" }.toSortedMap()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    grouped.forEach { (category, rows) ->
                        item(key = "hdr_$category") {
                            Text(
                                category,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(rows, key = { it.id }) { row ->
                            GroceryRow(
                                item = row,
                                onToggle = { vm.toggle(row) },
                                onQuantity = { vm.setQuantity(row, it) },
                                onDelete = { vm.remove(row) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddGroceryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, qty, unit, category ->
                vm.add(name, qty, unit, category)
                showAdd = false
            }
        )
    }

    if (showRecipePick) {
        RecipePickDialog(
            recipes = recipes,
            onDismiss = { showRecipePick = false },
            onPick = { vm.addRecipe(it); showRecipePick = false }
        )
    }
}

@Composable
private fun GroceryRow(
    item: GroceryItem,
    onToggle: () -> Unit,
    onQuantity: (Double) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${formatQty(item.quantity)} ${item.unit}${sourceHint(item.source)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onQuantity(item.quantity - 1) }, enabled = item.quantity > 1) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(formatQty(item.quantity), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onQuantity(item.quantity + 1) }) { Icon(Icons.Default.Add, contentDescription = "Add one") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
    }
}

private fun sourceHint(source: GrocerySource): String = when (source) {
    GrocerySource.LOW_STOCK -> " · running low"
    GrocerySource.RECIPE -> " · for a recipe"
    GrocerySource.MANUAL -> ""
}

@Composable
private fun AddGroceryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, qty: Double, unit: String, category: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("unit") }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to grocery list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it }, label = { Text("Qty") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), qty.toDoubleOrNull() ?: 1.0, unit.ifBlank { "unit" }, category.ifBlank { null }) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipePickDialog(
    recipes: List<Recipe>,
    onDismiss: () -> Unit,
    onPick: (Recipe) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Recipe?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a recipe's missing items") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick a recipe and Logistics adds only the ingredients you don't already have on the shelf.",
                    style = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selected?.name ?: "Choose a recipe",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Recipe") },
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        recipes.forEach { recipe ->
                            DropdownMenuItem(text = { Text(recipe.name) }, onClick = { selected = recipe; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onPick) }) { Text("Add missing") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
