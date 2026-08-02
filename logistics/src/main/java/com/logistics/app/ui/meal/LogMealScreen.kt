package com.logistics.app.ui.meal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Recipe
import com.logistics.app.data.model.PantryItem
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.data.repository.PantryRepository
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogMealViewModel(
    private val repo: PantryRepository,
    private val catalog: LifeOpsCatalog
) : ViewModel() {

    val items: StateFlow<List<PantryItem>> =
        repo.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recipes: StateFlow<List<Recipe>> =
        catalog.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var lastLogged by mutableStateOf<String?>(null)
        private set

    /** Pantry item ids whose linked food is an ingredient of [recipeId] — the auto-selection when
     *  you cook a known recipe. */
    suspend fun matchedPantryIds(recipeId: String): Set<String> {
        val ingredientFoodIds = catalog.observeIngredients(recipeId).first().map { it.foodItemId }.toSet()
        return items.value.filter { it.foodItemId != null && it.foodItemId in ingredientFoodIds }.map { it.id }.toSet()
    }

    fun logMeal(mealName: String, recipeId: String?, selections: Map<String, Double>) = viewModelScope.launch {
        val consumptions = selections.filter { it.value > 0.0 }
            .map { PantryRepository.Consumption(it.key, it.value) }
        if (consumptions.isEmpty()) return@launch
        repo.consumeMeal(mealName, recipeId, consumptions)
        lastLogged = "Logged \"$mealName\" — deducted ${consumptions.size} item(s)."
    }

    fun clearStatus() { lastLogged = null }

    class Factory(
        private val repo: PantryRepository,
        private val catalog: LifeOpsCatalog
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LogMealViewModel(repo, catalog) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMealScreen(vm: LogMealViewModel) {
    val pantryItems by vm.items.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var mealName by remember { mutableStateOf("") }
    var selectedRecipe by remember { mutableStateOf<Recipe?>(null) }
    // itemId -> amount text
    val amounts = remember { mutableStateMapOf<String, String>() }
    val included = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.lastLogged) {
        vm.lastLogged?.let {
            snackbar.showSnackbar(it)
            // Reset the form after a successful log.
            mealName = ""; selectedRecipe = null; included.clear(); amounts.clear()
            vm.clearStatus()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Log a meal", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Name the meal, mark what you used, and Logistics deducts it from your pantry — a running record of what went into each meal.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = mealName,
                    onValueChange = { mealName = it },
                    label = { Text("Meal name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RecipePicker(
                    recipes = recipes,
                    selected = selectedRecipe,
                    onSelect = { recipe ->
                        selectedRecipe = recipe
                        if (recipe != null) {
                            if (mealName.isBlank()) mealName = recipe.name
                            scope.launch {
                                val matched = vm.matchedPantryIds(recipe.id)
                                included.clear(); included.addAll(matched)
                                matched.forEach { amounts.putIfAbsent(it, "1") }
                            }
                        }
                    }
                )
            }
            HorizontalDivider()
            if (pantryItems.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Your pantry is empty — import an order first.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(pantryItems, key = { it.id }) { item ->
                        val isIncluded = item.id in included
                        MealItemRow(
                            item = item,
                            included = isIncluded,
                            amount = amounts[item.id] ?: "1",
                            onToggle = {
                                if (isIncluded) included.remove(item.id)
                                else { included.add(item.id); amounts.putIfAbsent(item.id, "1") }
                            },
                            onAmount = { amounts[item.id] = it }
                        )
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    val selections = included.associateWith { (amounts[it] ?: "1").toDoubleOrNull() ?: 0.0 }
                    Button(
                        onClick = { vm.logMeal(mealName.ifBlank { selectedRecipe?.name ?: "Meal" }, selectedRecipe?.id, selections) },
                        enabled = included.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) { Text("Log meal & deduct (${included.size})") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipePicker(
    recipes: List<Recipe>,
    selected: Recipe?,
    onSelect: (Recipe?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected?.name ?: "None (ad-hoc meal)",
            onValueChange = {},
            readOnly = true,
            label = { Text("From a LifeOps recipe (optional)") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("None (ad-hoc meal)") }, onClick = { onSelect(null); expanded = false })
            recipes.forEach { recipe ->
                DropdownMenuItem(text = { Text(recipe.name) }, onClick = { onSelect(recipe); expanded = false })
            }
        }
    }
}

@Composable
private fun MealItemRow(
    item: PantryItem,
    included: Boolean,
    amount: String,
    onToggle: () -> Unit,
    onAmount: (String) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = included, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Text("${formatQty(item.quantity)} ${item.unit} on hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (included) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = onAmount,
                    label = { Text("Used") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(90.dp)
                )
            }
        }
    }
}
