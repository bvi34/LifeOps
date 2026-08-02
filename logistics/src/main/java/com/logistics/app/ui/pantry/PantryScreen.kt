package com.logistics.app.ui.pantry

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
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
import com.logistics.app.data.model.PantryItem
import com.logistics.app.data.repository.PantryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PantryViewModel(private val repo: PantryRepository) : ViewModel() {

    val items: StateFlow<List<PantryItem>> =
        repo.observeItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun adjust(item: PantryItem, delta: Double) = viewModelScope.launch {
        repo.setQuantity(item.id, (item.quantity + delta), note = "Quick adjust")
    }

    fun setQuantity(item: PantryItem, value: Double) = viewModelScope.launch {
        repo.setQuantity(item.id, value)
    }

    fun delete(item: PantryItem) = viewModelScope.launch { repo.deleteItem(item.id) }

    fun addItem(name: String, quantity: Double, unit: String, category: String?) = viewModelScope.launch {
        repo.addItem(name, quantity, unit, category)
    }

    class Factory(private val repo: PantryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PantryViewModel(repo) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(vm: PantryViewModel) {
    val pantryItems by vm.items.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add item") }
            )
        }
    ) { padding ->
        if (pantryItems.isEmpty()) {
            EmptyPantry(Modifier.padding(padding))
        } else {
            val grouped = pantryItems.groupBy { it.category ?: "Other" }.toSortedMap()
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        PantryRow(
                            item = row,
                            onIncrement = { vm.adjust(row, 1.0) },
                            onDecrement = { vm.adjust(row, -1.0) },
                            onDelete = { vm.delete(row) }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddItemDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, qty, unit, category ->
                vm.addItem(name, qty, unit, category)
                showAdd = false
            }
        )
    }
}

@Composable
private fun EmptyPantry(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Your pantry is empty.\n\nImport a Walmart order or add items by hand to start tracking what's on your shelves.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun PantryRow(
    item: PantryItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatQty(item.quantity)} ${item.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.isLow) {
                        Spacer(Modifier.width(6.dp))
                        Text("LOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            IconButton(onClick = onDecrement) { Icon(Icons.Default.Remove, contentDescription = "Use one") }
            Text(formatQty(item.quantity), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onIncrement) { Icon(Icons.Default.Add, contentDescription = "Add one") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun AddItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, qty: Double, unit: String, category: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("unit") }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add pantry item") },
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

internal fun formatQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toInt().toString() else String.format("%.2f", q).trimEnd('0').trimEnd('.')
