package com.logistics.app.ui.food

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.util.DateUtil
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.ui.pantry.formatQty
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.operations.suite.ui.fields.SuiteNumberField

/** The per-day average over the trailing window, so a day reads against your own normal. */
data class NutritionAverage(
    val daysLogged: Int,
    val calories: Int,
    val carbsG: Int,
    val proteinG: Int,
    val fatG: Int
)

data class FoodUiState(
    val selectedDate: String = LocalDate.now().toString(),
    val weekStartDate: String = DateUtil.currentWeekStart().toString(),
    val entries: List<FoodLogEntry> = emptyList(),
    val plannedTotals: NutritionTotals = NutritionTotals.ZERO,
    val confirmedTotals: NutritionTotals = NutritionTotals.ZERO,
    val average: NutritionAverage? = null,
    val searchQuery: String = "",
    val searchResults: List<FoodItem> = emptyList(),
    /** Shown before you've typed anything — most logging is the same few dozen foods on repeat. */
    val suggestedFoods: List<FoodItem> = emptyList(),
    val showAddDialog: Boolean = false,
    val showPlanDialog: Boolean = false,
    val adjustingEntryId: String? = null,
    val status: String? = null
)

/**
 * The Food tab: LifeOps' food diary, opened from Logistics.
 *
 * Nothing here is a second copy of anything — [LifeOpsCatalog] writes through LifeOps' own food
 * service into LifeOps' diary, so a meal logged on this screen is the same row LifeOps' Daily Plan
 * shows, and a recipe planned here lands on the same week's menu. What Logistics adds is the
 * *context*: this is the app where the shelf, the shop and the cooking already live, and the
 * calories belong beside them rather than one app over.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest: re-subscribe the diary when the day changes.
class FoodViewModel(private val catalog: LifeOpsCatalog) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now().toString())

    private val _state = MutableStateFlow(FoodUiState())
    val state: StateFlow<FoodUiState> = _state.asStateFlow()

    val recipes: StateFlow<List<Recipe>> =
        catalog.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            selectedDate.flatMapLatest { catalog.observeDiary(it) }.collectLatest { entries ->
                _state.update {
                    it.copy(
                        selectedDate = selectedDate.value,
                        entries = entries,
                        plannedTotals = entries.filterNot { e -> e.confirmed }.sumTotals(),
                        confirmedTotals = entries.filter { e -> e.confirmed }.sumTotals()
                    )
                }
                refreshAverage()
            }
        }
    }

    /** Per-day averages over the trailing week — totals ÷ the days actually logged, so a week with
     *  three logged days averages over three, not seven. */
    private fun refreshAverage() = viewModelScope.launch {
        val since = DateUtil.isoFromEpoch(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        val entries = catalog.entriesSince(since).filter { it.confirmed }
        val average = if (entries.isEmpty()) null else {
            val days = entries.map { DateUtil.localDateKey(it.loggedAt) }.distinct().size.coerceAtLeast(1)
            NutritionAverage(
                daysLogged = days,
                calories = (entries.sumOf { it.calories } / days).toInt(),
                carbsG = (entries.sumOf { it.carbsG } / days).toInt(),
                proteinG = (entries.sumOf { it.proteinG } / days).toInt(),
                fatG = (entries.sumOf { it.fatG } / days).toInt()
            )
        }
        _state.update { it.copy(average = average) }
    }

    fun selectDate(date: String) {
        selectedDate.value = date
        _state.update { it.copy(selectedDate = date, adjustingEntryId = null) }
    }

    fun shiftWeek(weeks: Long) {
        val newStart = LocalDate.parse(_state.value.weekStartDate).plusWeeks(weeks)
        val today = LocalDate.now()
        val landing = if (!today.isBefore(newStart) && !today.isAfter(newStart.plusDays(6))) today else newStart
        _state.update { it.copy(weekStartDate = newStart.toString()) }
        selectDate(landing.toString())
    }

    fun clearStatus() = _state.update { it.copy(status = null) }

    // --- the add-food dialog ---

    fun showAddDialog() {
        _state.update { it.copy(showAddDialog = true, searchQuery = "", searchResults = emptyList()) }
        viewModelScope.launch {
            // Recent first, then the ones you reach for most; both come from LifeOps' own diary.
            val suggested = (catalog.recentFoods() + catalog.frequentFoods()).distinctBy { it.id }.take(12)
            _state.update { it.copy(suggestedFoods = suggested) }
        }
    }

    fun hideAddDialog() = _state.update { it.copy(showAddDialog = false) }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = catalog.searchFood(query)
            _state.update { it.copy(searchResults = results) }
        }
    }

    fun logFood(food: FoodItem, quantity: Double, unit: IngredientUnit) = viewModelScope.launch {
        val entry = catalog.logFood(food.id, quantity, unit, date = _state.value.selectedDate)
        _state.update {
            it.copy(
                showAddDialog = false,
                status = if (entry != null) "Logged ${food.name} — ${entry.calories.toInt()} kcal."
                else "${food.name} has no serving weight, so grams can't be converted."
            )
        }
    }

    fun logAdHoc(
        name: String,
        quantity: Double,
        unit: IngredientUnit,
        calories: Double,
        carbsG: Double,
        proteinG: Double,
        fatG: Double
    ) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        catalog.logAdHoc(name, quantity, unit, calories, carbsG, proteinG, fatG, date = _state.value.selectedDate)
        _state.update { it.copy(showAddDialog = false, status = "Logged $name.") }
    }

    // --- entries ---

    fun confirm(entryId: String) = viewModelScope.launch { catalog.confirmEntry(entryId) }

    fun startAdjusting(entryId: String) = _state.update { it.copy(adjustingEntryId = entryId) }
    fun cancelAdjusting() = _state.update { it.copy(adjustingEntryId = null) }

    fun adjust(entryId: String, quantity: Double, unit: IngredientUnit) = viewModelScope.launch {
        catalog.adjustEntry(entryId, quantity, unit)
        _state.update { it.copy(adjustingEntryId = null) }
    }

    /** Promotes a one-off entry into a permanent catalog food — type "my protein shake" once. */
    fun saveAsFood(entryId: String) = viewModelScope.launch {
        val food = catalog.promoteToCustomFood(entryId)
        _state.update {
            it.copy(status = food?.let { f -> "Saved \"${f.name}\" to your food catalog." })
        }
    }

    fun unplan(menuItemId: String) = viewModelScope.launch { catalog.unplanMeal(menuItemId) }

    // --- planning a recipe onto the day ---

    fun showPlanDialog() = _state.update { it.copy(showPlanDialog = true) }
    fun hidePlanDialog() = _state.update { it.copy(showPlanDialog = false) }

    fun planMeal(recipeId: String, servings: Double, mealType: String?) = viewModelScope.launch {
        runCatching { catalog.planMeal(_state.value.selectedDate, recipeId, servings, mealType) }
            .onSuccess { _state.update { s -> s.copy(showPlanDialog = false, status = "Planned \"${it.mealName}\".") } }
            .onFailure { e ->
                _state.update { s -> s.copy(showPlanDialog = false, status = e.message ?: "Couldn't plan that meal.") }
            }
    }

    class Factory(private val catalog: LifeOpsCatalog) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FoodViewModel(catalog) as T
    }
}

private fun List<FoodLogEntry>.sumTotals(): NutritionTotals = fold(NutritionTotals.ZERO) { acc, e ->
    acc + NutritionTotals(e.calories, e.carbsG, e.proteinG, e.fatG)
}

@Composable
fun FoodScreen(vm: FoodViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.status) {
        state.status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.showAddDialog() }) {
                Icon(Icons.Default.Add, contentDescription = "Log food")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            WeekRow(
                weekStartDate = state.weekStartDate,
                selectedDate = state.selectedDate,
                onSelect = { vm.selectDate(it) },
                onShiftWeek = { vm.shiftWeek(it) }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { TotalsCard(confirmed = state.confirmedTotals, planned = state.plannedTotals) }
                state.average?.let { item { AverageCard(it) } }
                item {
                    OutlinedButton(onClick = { vm.showPlanDialog() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Plan a meal from a recipe")
                    }
                }
                if (state.entries.isEmpty()) {
                    item {
                        Text(
                            "Nothing logged or planned for this day yet. Tap + to log what you ate, or plan a recipe onto it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            isAdjusting = state.adjustingEntryId == entry.id,
                            onConfirm = { vm.confirm(entry.id) },
                            onStartAdjust = { vm.startAdjusting(entry.id) },
                            onCancelAdjust = { vm.cancelAdjusting() },
                            onAdjust = { qty -> vm.adjust(entry.id, qty, entry.unit) },
                            onSaveAsFood = { vm.saveAsFood(entry.id) },
                            onUnplan = { entry.weeklyMenuItemId?.let { vm.unplan(it) } }
                        )
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddFoodDialog(
            searchQuery = state.searchQuery,
            results = state.searchResults.ifEmpty { if (state.searchQuery.isBlank()) state.suggestedFoods else emptyList() },
            resultsAreSuggestions = state.searchQuery.isBlank() && state.searchResults.isEmpty(),
            onQueryChange = { vm.onSearchQueryChange(it) },
            onDismiss = { vm.hideAddDialog() },
            onLogFood = { food, qty, unit -> vm.logFood(food, qty, unit) },
            onLogAdHoc = { name, qty, unit, cal, carbs, protein, fat ->
                vm.logAdHoc(name, qty, unit, cal, carbs, protein, fat)
            }
        )
    }

    if (state.showPlanDialog) {
        PlanMealDialog(
            recipes = recipes,
            date = state.selectedDate,
            onDismiss = { vm.hidePlanDialog() },
            onPlan = { recipeId, servings, mealType -> vm.planMeal(recipeId, servings, mealType) }
        )
    }
}

@Composable
private fun WeekRow(
    weekStartDate: String,
    selectedDate: String,
    onSelect: (String) -> Unit,
    onShiftWeek: (Long) -> Unit
) {
    val dates = remember(weekStartDate) {
        val start = LocalDate.parse(weekStartDate)
        (0..6).map { start.plusDays(it.toLong()).toString() }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onShiftWeek(-1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            dates.forEach { date ->
                FilterChip(
                    selected = date == selectedDate,
                    onClick = { onSelect(date) },
                    label = { Text(DateUtil.formatDate(date)) }
                )
            }
        }
        IconButton(onClick = { onShiftWeek(1) }) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
        }
    }
}

@Composable
private fun TotalsCard(confirmed: NutritionTotals, planned: NutritionTotals) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("The day's totals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TotalsColumn("Logged", confirmed, Modifier.weight(1f))
                TotalsColumn("Planned", planned, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TotalsColumn(label: String, totals: NutritionTotals, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Text("${totals.calories.toInt()} kcal", style = MaterialTheme.typography.titleMedium)
        Text(
            "C ${totals.carbsG.toInt()}g · P ${totals.proteinG.toInt()}g · F ${totals.fatG.toInt()}g",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AverageCard(average: NutritionAverage) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Last 7 days · per-day average over ${average.daysLogged} logged day${if (average.daysLogged == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("${average.calories} kcal", style = MaterialTheme.typography.titleMedium)
            Text(
                "C ${average.carbsG}g · P ${average.proteinG}g · F ${average.fatG}g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: FoodLogEntry,
    isAdjusting: Boolean,
    onConfirm: () -> Unit,
    onStartAdjust: () -> Unit,
    onCancelAdjust: () -> Unit,
    onAdjust: (Double) -> Unit,
    onSaveAsFood: () -> Unit,
    onUnplan: () -> Unit
) {
    // A planned meal is a suggestion until it's confirmed, so it can be dropped outright; an entry
    // you actually ate can only be adjusted.
    val isPlanned = !entry.confirmed && entry.source == FoodLogSource.PLANNED && entry.weeklyMenuItemId != null
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${formatQty(entry.quantity)} ${entry.unit.name.lowercase()} · ${entry.calories.toInt()} kcal" +
                            if (isPlanned) " · planned" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "C ${entry.carbsG.toInt()}g · P ${entry.proteinG.toInt()}g · F ${entry.fatG.toInt()}g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!entry.confirmed) {
                    IconButton(onClick = onConfirm) { Icon(Icons.Default.Check, contentDescription = "Confirm") }
                }
                if (isPlanned) {
                    IconButton(onClick = onUnplan) { Icon(Icons.Default.Close, contentDescription = "Remove planned meal") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onStartAdjust) { Text("Adjust") }
                // Only a one-off entry can become a food; one logged from the catalog already is one.
                if (entry.foodItemId == null && entry.confirmed) {
                    TextButton(onClick = onSaveAsFood) { Text("Save as food") }
                }
            }
            if (isAdjusting) {
                var quantityText by rememberSaveable(entry.id) { mutableStateOf(formatQty(entry.quantity)) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity (${entry.unit.name.lowercase()})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onAdjust(quantityText.toDoubleOrNull() ?: entry.quantity) }) { Text("Save") }
                    TextButton(onClick = onCancelAdjust) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun AddFoodDialog(
    searchQuery: String,
    results: List<FoodItem>,
    resultsAreSuggestions: Boolean,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onLogFood: (FoodItem, Double, IngredientUnit) -> Unit,
    onLogAdHoc: (String, Double, IngredientUnit, Double, Double, Double, Double) -> Unit
) {
    var adHoc by rememberSaveable { mutableStateOf(false) }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(IngredientUnit.SERVING) }
    var name by rememberSaveable { mutableStateOf("") }
    var calories by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adHoc) "Log something not in the catalog" else "Log food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Amount") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    // LifeOps' nutrition maths is strict on purpose: a quantity is either servings of
                    // the food or grams of it, never a guessed cup-to-gram conversion.
                    FilterChip(
                        selected = unit == IngredientUnit.SERVING,
                        onClick = { unit = IngredientUnit.SERVING },
                        label = { Text("servings") }
                    )
                    FilterChip(
                        selected = unit == IngredientUnit.GRAM,
                        onClick = { unit = IngredientUnit.GRAM },
                        label = { Text("grams") }
                    )
                }
                if (!adHoc) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        label = { Text("Search your food catalog") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (results.isNotEmpty()) {
                        Text(
                            if (resultsAreSuggestions) "Recent and frequent" else "${results.size} match(es)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(results, key = { it.id }) { food ->
                            TextButton(
                                onClick = { onLogFood(food, quantity.toDoubleOrNull() ?: 1.0, unit) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(food.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${food.calories.toInt()} kcal per ${formatQty(food.servingSize)} ${food.servingUnit}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = { adHoc = true }) { Text("Not in there? Enter it by hand") }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SuiteNumberField(label = "Calories", value = calories, decimals = true, onValueChange = { calories = it })
                    SuiteNumberField(label = "Carbs (g)", value = carbs, decimals = true, onValueChange = { carbs = it })
                    SuiteNumberField(label = "Protein (g)", value = protein, decimals = true, onValueChange = { protein = it })
                    SuiteNumberField(label = "Fat (g)", value = fat, decimals = true, onValueChange = { fat = it })
                    TextButton(onClick = { adHoc = false }) { Text("Back to search") }
                }
            }
        },
        confirmButton = {
            if (adHoc) {
                TextButton(
                    onClick = {
                        onLogAdHoc(
                            name.trim(),
                            quantity.toDoubleOrNull() ?: 1.0,
                            unit,
                            calories.toDoubleOrNull() ?: 0.0,
                            carbs.toDoubleOrNull() ?: 0.0,
                            protein.toDoubleOrNull() ?: 0.0,
                            fat.toDoubleOrNull() ?: 0.0
                        )
                    },
                    enabled = name.isNotBlank()
                ) { Text("Log it") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


/**
 * Plans a recipe onto the selected day. The recipe book is the only source here — a meal you can't
 * name from the book is an ad-hoc log, which the + button already covers.
 */
@Composable
private fun PlanMealDialog(
    recipes: List<Recipe>,
    date: String,
    onDismiss: () -> Unit,
    onPlan: (recipeId: String, servings: Double, mealType: String?) -> Unit
) {
    var selectedRecipeId by rememberSaveable { mutableStateOf<String?>(null) }
    var servings by rememberSaveable { mutableStateOf("1") }
    var mealType by rememberSaveable { mutableStateOf(MEAL_TYPES.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plan a meal for ${DateUtil.formatDate(date)}") },
        text = {
            if (recipes.isEmpty()) {
                Text("No recipes yet — import one in the Recipes tab first.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        MEAL_TYPES.forEach { type ->
                            FilterChip(
                                selected = mealType == type,
                                onClick = { mealType = type },
                                label = { Text(type.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = servings,
                        onValueChange = { servings = it },
                        label = { Text("Servings") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
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
                onClick = { selectedRecipeId?.let { onPlan(it, servings.toDoubleOrNull() ?: 1.0, mealType) } },
                enabled = selectedRecipeId != null
            ) { Text("Plan it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val MEAL_TYPES = listOf("breakfast", "lunch", "snack", "dinner")
