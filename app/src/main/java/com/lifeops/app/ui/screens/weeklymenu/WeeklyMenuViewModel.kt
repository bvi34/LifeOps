package com.lifeops.app.ui.screens.weeklymenu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.WeeklyMenuItem
import com.lifeops.app.data.repository.RecipeRepository
import com.lifeops.app.data.repository.WeeklyMenuItemRepository
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeeklyMenuUiState(
    val weekStartDate: String = "",
    val floatingMeals: List<WeeklyMenuItem> = emptyList(),
    val pinnedMeals: List<WeeklyMenuItem> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val showAddDialog: Boolean = false,
    val assigningItemId: String? = null
)

class WeeklyMenuViewModel(
    private val weeklyMenuItemRepository: WeeklyMenuItemRepository,
    private val recipeRepository: RecipeRepository
) : ViewModel() {

    private val _weekStartDate = MutableStateFlow(DateUtil.currentWeekStart().toString())

    private val _uiState = MutableStateFlow(WeeklyMenuUiState(weekStartDate = _weekStartDate.value))
    val uiState: StateFlow<WeeklyMenuUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            recipeRepository.observeAll().collectLatest { recipes ->
                _uiState.update { it.copy(recipes = recipes) }
            }
        }
        viewModelScope.launch {
            _weekStartDate.flatMapLatest { weeklyMenuItemRepository.observeForWeek(it) }
                .collectLatest { items ->
                    _uiState.update {
                        it.copy(
                            weekStartDate = _weekStartDate.value,
                            floatingMeals = items.filter { item -> item.assignedDate == null },
                            pinnedMeals = items.filter { item -> item.assignedDate != null }
                                .sortedBy { item -> item.assignedDate }
                        )
                    }
                }
        }
    }

    fun setWeekStartDate(date: String) {
        _weekStartDate.value = date
    }

    fun showAddDialog() = _uiState.update { it.copy(showAddDialog = true) }
    fun hideAddDialog() = _uiState.update { it.copy(showAddDialog = false) }

    fun addMeal(mealName: String, recipeId: String?, plannedServings: Double, mealType: String?) {
        viewModelScope.launch {
            weeklyMenuItemRepository.createMenuItem(
                weekStartDate = _weekStartDate.value,
                mealName = mealName,
                recipeId = recipeId,
                plannedServings = plannedServings,
                mealType = mealType
            )
            _uiState.update { it.copy(showAddDialog = false) }
        }
    }

    fun startAssigning(itemId: String) = _uiState.update { it.copy(assigningItemId = itemId) }
    fun cancelAssigning() = _uiState.update { it.copy(assigningItemId = null) }

    fun assignToDate(itemId: String, date: String) {
        viewModelScope.launch {
            weeklyMenuItemRepository.assignToDate(itemId, date)
            _uiState.update { it.copy(assigningItemId = null) }
        }
    }

    fun unassign(itemId: String) {
        viewModelScope.launch { weeklyMenuItemRepository.unassign(itemId) }
    }

    fun delete(itemId: String) {
        viewModelScope.launch { weeklyMenuItemRepository.delete(itemId) }
    }
}

class WeeklyMenuViewModelFactory(
    private val weeklyMenuItemRepository: WeeklyMenuItemRepository,
    private val recipeRepository: RecipeRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WeeklyMenuViewModel(weeklyMenuItemRepository, recipeRepository) as T
}
