package com.logistics.app.ui.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
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

    /** Set (or clear, with null) the low-stock alert level that flags a row as running low and feeds
     *  the grocery list's "Restock low". */
    fun setThreshold(item: PantryItem, threshold: Double?) = viewModelScope.launch {
        repo.updateThreshold(item.id, threshold)
    }

    fun delete(item: PantryItem) = viewModelScope.launch { repo.deleteItem(item.id) }

    fun addItem(name: String, quantity: Double, unit: String, category: String?) = viewModelScope.launch {
        repo.addItem(name, quantity, unit, category)
    }

    /** Break one stock line into individual pieces — see [PantryRepository.splitItem]. */
    fun split(item: PantryItem, pieces: Double, unit: String) = viewModelScope.launch {
        repo.splitItem(item.id, pieces, unit)
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
    var splitTarget by remember { mutableStateOf<PantryItem?>(null) }
    var editTarget by remember { mutableStateOf<PantryItem?>(null) }

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
                            onDelete = { vm.delete(row) },
                            onSplit = { splitTarget = row },
                            onEdit = { editTarget = row }
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

    splitTarget?.let { target ->
        SplitItemDialog(
            item = target,
            onDismiss = { splitTarget = null },
            onConfirm = { pieces, unit ->
                vm.split(target, pieces, unit)
                splitTarget = null
            }
        )
    }

    editTarget?.let { target ->
        EditItemDialog(
            item = target,
            onDismiss = { editTarget = null },
            onConfirm = { quantity, threshold ->
                vm.setQuantity(target, quantity)
                vm.setThreshold(target, threshold)
                editTarget = null
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
    onDelete: () -> Unit,
    onSplit: () -> Unit,
    onEdit: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tap the name/qty to set an exact amount and a low-stock alert level.
            Column(Modifier.weight(1f).clickable { onEdit() }) {
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
            IconButton(onClick = onSplit) { Icon(Icons.Default.CallSplit, contentDescription = "Break into pieces") }
            IconButton(onClick = onDecrement) { Icon(Icons.Default.Remove, contentDescription = "Use one") }
            Text(formatQty(item.quantity), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onIncrement) { Icon(Icons.Default.Add, contentDescription = "Add one") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

/**
 * "Break into pieces" — repackage one stock line at a finer granularity. Prefilled from the item's
 * current stock, with a live preview of the before/after so "2 lb → 3 meals" is obvious before you
 * commit.
 */
@Composable
private fun SplitItemDialog(
    item: PantryItem,
    onDismiss: () -> Unit,
    onConfirm: (pieces: Double, unit: String) -> Unit
) {
    var pieces by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("piece") }
    val piecesValue = pieces.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Break into pieces") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Re-express \"${item.name}\" at a finer granularity — same stock, counted your way. " +
                        "e.g. 2 lb into 3 meals, or a 58-count box into 58 pieces.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pieces, onValueChange = { pieces = it }, label = { Text("Count") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it }, label = { Text("Piece unit") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                if (piecesValue != null && piecesValue > 0.0) {
                    Text(
                        "${formatQty(item.quantity)} ${item.unit} → ${formatQty(piecesValue)} ${unit.ifBlank { "piece" }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = piecesValue != null && piecesValue > 0.0,
                onClick = { onConfirm(piecesValue ?: 0.0, unit.ifBlank { "piece" }) }
            ) { Text("Break up") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Edit an existing stock line: set its exact quantity and its low-stock alert level. The threshold is
 * what turns on the "LOW" flag and what the grocery list's "Restock low" sweeps for, so this is where
 * the pantry and the shopping list connect. A blank threshold clears the alert.
 */
@Composable
private fun EditItemDialog(
    item: PantryItem,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Double, threshold: Double?) -> Unit
) {
    var qty by remember { mutableStateOf(formatQty(item.quantity)) }
    var threshold by remember { mutableStateOf(item.lowStockThreshold?.let { formatQty(it) } ?: "") }
    val qtyValue = qty.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it },
                    label = { Text("On hand (${item.unit})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = threshold, onValueChange = { threshold = it },
                    label = { Text("Alert me at or below") },
                    supportingText = { Text("Feeds \"Restock low\" on your grocery list. Leave blank for no alert.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = qtyValue != null,
                onClick = { onConfirm(qtyValue ?: item.quantity, threshold.toDoubleOrNull()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
