package com.lifeops.app.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.data.model.Recipe
import com.lifeops.app.data.model.RecipeIngredient
import com.lifeops.app.data.model.RecipeNutrition
import com.lifeops.app.data.repository.FoodItemRepository
import com.lifeops.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IngredientRow(val ingredient: RecipeIngredient, val foodName: String)

data class RecipeDetailUiState(
    val recipe: Recipe? = null,
    val ingredients: List<IngredientRow> = emptyList(),
    val nutrition: RecipeNutrition? = null,
    val showEditDialog: Boolean = false,
    val showAddIngredientDialog: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FoodItem> = emptyList()
)

class RecipeDetailViewModel(
    private val recipeId: String,
    private val recipeRepository: RecipeRepository,
    private val foodItemRepository: FoodItemRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecipeDetailUiState())
    val uiState: StateFlow<RecipeDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                recipeRepository.observeById(recipeId),
                recipeRepository.observeIngredients(recipeId)
            ) { recipe, ingredients -> recipe to ingredients }
                .collectLatest { (recipe, ingredients) ->
                    val rows = ingredients.map { ingredient ->
                        val name = foodItemRepository.getById(ingredient.foodItemId)?.name ?: "Unknown food"
                        IngredientRow(ingredient, name)
                    }
                    val nutrition = recipeRepository.getNutrition(recipeId)
                    _uiState.update { it.copy(recipe = recipe, ingredients = rows, nutrition = nutrition) }
                }
        }
    }

    fun showEditDialog() = _uiState.update { it.copy(showEditDialog = true) }
    fun hideEditDialog() = _uiState.update { it.copy(showEditDialog = false) }

    fun rename(name: String, servings: Double) {
        val recipe = _uiState.value.recipe ?: return
        viewModelScope.launch {
            recipeRepository.updateRecipe(recipe, name, servings)
            _uiState.update { it.copy(showEditDialog = false) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            recipeRepository.deleteRecipe(recipeId)
            onDeleted()
        }
    }

    fun showAddIngredientDialog() = _uiState.update { it.copy(showAddIngredientDialog = true, searchQuery = "", searchResults = emptyList()) }
    fun hideAddIngredientDialog() = _uiState.update { it.copy(showAddIngredientDialog = false) }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val results = foodItemRepository.search(query)
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun addIngredient(foodItemId: String, quantity: Double, unit: IngredientUnit) {
        viewModelScope.launch {
            recipeRepository.addIngredient(recipeId, foodItemId, quantity, unit)
            _uiState.update { it.copy(showAddIngredientDialog = false) }
        }
    }

    fun removeIngredient(id: String) {
        viewModelScope.launch { recipeRepository.removeIngredient(id) }
    }
}

class RecipeDetailViewModelFactory(
    private val recipeId: String,
    private val recipeRepository: RecipeRepository,
    private val foodItemRepository: FoodItemRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RecipeDetailViewModel(recipeId, recipeRepository, foodItemRepository) as T
}
