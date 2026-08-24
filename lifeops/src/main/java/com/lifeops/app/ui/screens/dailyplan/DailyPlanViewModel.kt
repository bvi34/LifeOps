package com.lifeops.app.ui.screens.dailyplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodLogEntry
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.NutritionTotals
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.repository.FoodItemRepository
import com.lifeops.app.data.repository.FoodLogRepository
import com.lifeops.app.data.repository.RecipeRepository
import com.lifeops.app.data.repository.WeeklyMenuRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyPlanUiState(
    val weekStartDate: String = "",
    val selectedDate: String = "",
    val entries: List<FoodLogEntry> = emptyList(),
    val plannedTotals: NutritionTotals = NutritionTotals.ZERO,
    val confirmedTotals: NutritionTotals = NutritionTotals.ZERO,
    val searchQuery: String = "",
    val searchResults: List<FoodItem> = emptyList(),
    val showAddDialog: Boolean = false,
    val adjustingEntryId: String? = null,
    /** The recipe book, for planning a meal onto this day. */
    val recipes: List<Recipe> = emptyList(),
    val showPlanDialog: Boolean = false
)

class DailyPlanViewModel(
    private val foodLogRepository: FoodLogRepository,
    private val foodItemRepository: FoodItemRepository,
    private val recipeRepository: RecipeRepository,
    weeklyMenuRepository: WeeklyMenuRepository
) : ViewModel() {

    private val foodService =
        com.lifeops.app.connection.service.FoodService(foodItemRepository, foodLogRepository)
    private val mealPlanService = com.lifeops.app.connection.service.MealPlanService(
        weeklyMenuRepository, recipeRepository, foodLogRepository
    )

    private val _weekStartDate = MutableStateFlow(DateUtil.currentWeekStart().toString())
    private val _selectedDate = MutableStateFlow(LocalDate.now().toString())

    private val _uiState = MutableStateFlow(
        DailyPlanUiState(weekStartDate = _weekStartDate.value, selectedDate = _selectedDate.value)
    )
    val uiState: StateFlow<DailyPlanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedDate.flatMapLatest { foodLogRepository.observeForDate(it) }
                .collectLatest { entries ->
                    val confirmed = entries.filter { it.confirmed }
                    val planned = entries.filter { !it.confirmed }
                    _uiState.update {
                        it.copy(
                            selectedDate = _selectedDate.value,
                            entries = entries,
                            plannedTotals = planned.sumTotals(),
                            confirmedTotals = confirmed.sumTotals()
                        )
                    }
                }
        }
        viewModelScope.launch {
            recipeRepository.observeAll().collectLatest { recipes ->
                _uiState.update { it.copy(recipes = recipes) }
            }
        }
    }

    /** Re-keys the selected day to stay within the newly selected week, defaulting to today
     *  when today falls inside that week, else the week's Monday. */
    fun setWeekStartDate(date: String) {
        _weekStartDate.value = date
        val start = LocalDate.parse(date)
        val end = start.plusDays(6)
        val today = LocalDate.now()
        val newSelected = if (!today.isBefore(start) && !today.isAfter(end)) today else start
        _selectedDate.value = newSelected.toString()
        _uiState.update { it.copy(weekStartDate = date) }
    }

    fun selectDate(date: String) {
        _selectedDate.value = date
    }

    fun confirmEntry(entryId: String) {
        viewModelScope.launch { foodService.confirmEntry(entryId) }
    }

    fun startAdjusting(entryId: String) = _uiState.update { it.copy(adjustingEntryId = entryId) }
    fun cancelAdjusting() = _uiState.update { it.copy(adjustingEntryId = null) }

    fun adjustEntry(entryId: String, quantity: Double, unit: IngredientUnit) {
        viewModelScope.launch {
            foodService.adjustEntry(entryId, quantity, unit)
            _uiState.update { it.copy(adjustingEntryId = null) }
        }
    }

    fun showPlanDialog() = _uiState.update { it.copy(showPlanDialog = true) }
    fun hidePlanDialog() = _uiState.update { it.copy(showPlanDialog = false) }

    /** Commits a recipe to the selected day; the planned entry shows up in the day's list. */
    fun planMeal(recipeId: String, servings: Double, mealType: String?) {
        viewModelScope.launch {
            mealPlanService.plan(
                date = _selectedDate.value,
                recipeId = recipeId,
                servings = servings,
                mealType = mealType
            )
            _uiState.update { it.copy(showPlanDialog = false) }
        }
    }

    /** Takes a planned meal back off the day (a confirmed entry is kept — it's history by then). */
    fun unplan(weeklyMenuItemId: String) {
        viewModelScope.launch { mealPlanService.unplan(weeklyMenuItemId) }
    }

    fun showAddDialog() = _uiState.update { it.copy(showAddDialog = true, searchQuery = "", searchResults = emptyList()) }
    fun hideAddDialog() = _uiState.update { it.copy(showAddDialog = false) }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = foodItemRepository.search(query)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun addFoodItem(foodItemId: String, quantity: Double, unit: IngredientUnit) {
        viewModelScope.launch {
            foodService.logFood(foodItemId, quantity, unit)
            _uiState.update { it.copy(showAddDialog = false) }
        }
    }

    fun addAdHoc(name: String, quantity: Double, unit: IngredientUnit, calories: Double, carbsG: Double, proteinG: Double, fatG: Double) {
        viewModelScope.launch {
            foodService.logAdHoc(name, quantity, unit, calories, carbsG, proteinG, fatG)
            _uiState.update { it.copy(showAddDialog = false) }
        }
    }
}

private fun List<FoodLogEntry>.sumTotals(): NutritionTotals = fold(NutritionTotals.ZERO) { acc, entry ->
    acc + NutritionTotals(entry.calories, entry.carbsG, entry.proteinG, entry.fatG)
}

class DailyPlanViewModelFactory(
    private val foodLogRepository: FoodLogRepository,
    private val foodItemRepository: FoodItemRepository,
    private val recipeRepository: RecipeRepository,
    private val weeklyMenuRepository: WeeklyMenuRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DailyPlanViewModel(foodLogRepository, foodItemRepository, recipeRepository, weeklyMenuRepository) as T
}
