package com.logistics.app.ui.recipe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Recipe
import com.logistics.app.data.model.ParsedRecipe
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.net.RecipeFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RecipeImportState {
    object Idle : RecipeImportState
    object Working : RecipeImportState
    data class Preview(val recipe: ParsedRecipe) : RecipeImportState
    data class Done(val name: String) : RecipeImportState
    data class Error(val message: String) : RecipeImportState
}

class RecipeViewModel(private val catalog: LifeOpsCatalog) : ViewModel() {

    val recipes: StateFlow<List<Recipe>> =
        catalog.observeRecipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _import = MutableStateFlow<RecipeImportState>(RecipeImportState.Idle)
    val importState: StateFlow<RecipeImportState> = _import.asStateFlow()

    fun reset() { _import.value = RecipeImportState.Idle }

    fun fetch(url: String) = viewModelScope.launch {
        if (url.isBlank()) { _import.value = RecipeImportState.Error("Enter a recipe link first."); return@launch }
        _import.value = RecipeImportState.Working
        runCatching { RecipeFetcher.fetch(url) }
            .onSuccess { _import.value = RecipeImportState.Preview(it) }
            .onFailure { _import.value = RecipeImportState.Error(it.message ?: "Couldn't import that link.") }
    }

    fun save(parsed: ParsedRecipe) = viewModelScope.launch {
        _import.value = RecipeImportState.Working
        runCatching { catalog.createImportedRecipe(parsed) }
            .onSuccess { _import.value = RecipeImportState.Done(it.name) }
            .onFailure { _import.value = RecipeImportState.Error(it.message ?: "Couldn't save the recipe.") }
    }

    class Factory(private val catalog: LifeOpsCatalog) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecipeViewModel(catalog) as T
    }
}

@Composable
fun RecipeScreen(vm: RecipeViewModel, initialUrl: String? = null) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }

    // Auto-fetch a shared link the first time it arrives.
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) vm.fetch(initialUrl)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Recipes", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Grab a recipe from any site — Logistics reads its structured recipe data and saves it into LifeOps' recipe book, ingredients and all.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Recipe URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            when (val s = importState) {
                is RecipeImportState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is RecipeImportState.Error -> {
                    Column {
                        Button(onClick = { vm.fetch(url) }, modifier = Modifier.fillMaxWidth()) { Text("Import from link") }
                        Spacer(Modifier.height(6.dp))
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is RecipeImportState.Done -> {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Saved \"${s.name}\" to LifeOps", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { url = ""; vm.reset() }) { Text("Import another") }
                        }
                    }
                }
                is RecipeImportState.Preview -> RecipePreviewCard(
                    parsed = s.recipe,
                    onSave = { vm.save(s.recipe) },
                    onCancel = { vm.reset() }
                )
                RecipeImportState.Idle -> Button(onClick = { vm.fetch(url) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Import from link")
                }
            }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { Text("In your LifeOps recipe book (${recipes.size})", style = MaterialTheme.typography.titleSmall) }

        if (recipes.isEmpty()) {
            item { Text("No recipes yet.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(recipes, key = { it.id }) { recipe ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(recipe.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${recipe.servings} serving(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipePreviewCard(
    parsed: ParsedRecipe,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(parsed.name, style = MaterialTheme.typography.titleMedium)
            parsed.servings?.let { Text("${it.toInt()} serving(s)", style = MaterialTheme.typography.bodySmall) }
            Text("${parsed.ingredients.size} ingredients", style = MaterialTheme.typography.labelMedium)
            parsed.ingredients.take(20).forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodySmall)
            }
            if (parsed.ingredients.size > 20) Text("…and ${parsed.ingredients.size - 20} more", style = MaterialTheme.typography.bodySmall)
            Text(
                if (parsed.steps.isEmpty()) "No method on the page" else "${parsed.steps.size} steps",
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save to LifeOps") }
            }
        }
    }
}
