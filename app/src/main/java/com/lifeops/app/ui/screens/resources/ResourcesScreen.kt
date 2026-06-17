@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.resources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.model.GameResourceMapping
import com.lifeops.app.data.model.ResourceTransaction
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.theme.parseColor

@Composable
fun ResourcesScreen(viewModel: ResourcesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader() }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Aspect Earnings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                AspectEarningsCard(
                    aspects = state.aspects,
                    earnedThisWeek = state.aspectEarnedThisWeek,
                    lifetimeEarned = state.aspectLifetimeEarned
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                Text("Game Resources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }
            items(state.gameResources, key = { it.id }) { resource ->
                GameResourceCard(
                    resource = resource,
                    mappings = state.mappings.filter { it.gameResourceId == resource.id },
                    aspects = state.aspects,
                    earnedThisWeek = viewModel.computeResourceEarnedThisWeek(resource.id),
                    recentTransactions = state.transactions[resource.id] ?: emptyList(),
                    onRename = { viewModel.onRenameResource(resource, it) },
                    onEditMappings = { viewModel.setEditingResource(resource.id) },
                    onSpend = { viewModel.showSpendDialog(resource.id) }
                )
            }
        }
    }

    state.editingMappingResourceId?.let { resourceId ->
        val resource = state.gameResources.firstOrNull { it.id == resourceId }
        if (resource != null) {
            MappingDialog(
                resource = resource,
                currentMappings = state.mappings.filter { it.gameResourceId == resourceId },
                aspects = state.aspects,
                onAdd = { aspectId, weight -> viewModel.onAddOrUpdateMapping(resourceId, aspectId, weight) },
                onDelete = viewModel::onDeleteMapping,
                onDismiss = { viewModel.setEditingResource(null) }
            )
        }
    }

    state.spendingResourceId?.let { resourceId ->
        val resource = state.gameResources.firstOrNull { it.id == resourceId }
        if (resource != null) {
            SpendDialog(
                resource = resource,
                onConfirm = { amount, note -> viewModel.onSpendResource(resourceId, amount, note) },
                onDismiss = viewModel::hideSpendDialog
            )
        }
    }
}

@Composable
private fun GameResourceCard(
    resource: GameResource,
    mappings: List<GameResourceMapping>,
    aspects: List<com.lifeops.app.data.model.Aspect>,
    earnedThisWeek: Int,
    recentTransactions: List<ResourceTransaction>,
    onRename: (String) -> Unit,
    onEditMappings: () -> Unit,
    onSpend: () -> Unit
) {
    var editingName by remember { mutableStateOf(false) }
    var nameValue by remember(resource.id) { mutableStateOf(resource.name) }
    var showHistory by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editingName) {
                    OutlinedTextField(
                        value = nameValue,
                        onValueChange = { nameValue = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(onClick = {
                        onRename(nameValue)
                        editingName = false
                    }) { Text("Save") }
                } else {
                    Text(
                        text = resource.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { editingName = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip("This Week", "+$earnedThisWeek")
                StatChip("Balance", resource.currentValue.toString())
                StatChip("Lifetime", resource.lifetimeEarned.toString())
            }
            if (mappings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Mapped aspects:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                mappings.forEach { mapping ->
                    val aspectName = aspects.firstOrNull { it.id == mapping.aspectId }?.name ?: mapping.aspectId
                    Text("• $aspectName ×${mapping.weight}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditMappings, modifier = Modifier.weight(1f)) { Text("Edit Mappings") }
                Button(
                    onClick = onSpend,
                    enabled = resource.currentValue > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Spend") }
            }
            if (recentTransactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showHistory = !showHistory },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showHistory) "Hide history" else "Show history (${recentTransactions.size})",
                        style = MaterialTheme.typography.labelSmall)
                }
                if (showHistory) {
                    recentTransactions.take(5).forEach { tx ->
                        TransactionRow(tx)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: ResourceTransaction) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isSpend = tx.type == "spend"
        Text(
            text = if (isSpend) "−${tx.amount}" else "+${tx.amount}",
            style = MaterialTheme.typography.bodySmall,
            color = if (isSpend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = tx.note ?: tx.type.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = tx.createdAt.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun SpendDialog(
    resource: GameResource,
    onConfirm: (Int, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amountInt = amount.toIntOrNull()
    val valid = amountInt != null && amountInt > 0 && amountInt <= resource.currentValue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spend ${resource.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Balance: ${resource.currentValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount to spend") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = amountInt != null && amountInt > resource.currentValue
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { amountInt?.let { onConfirm(it, note.trim().ifBlank { null }) } },
                enabled = valid
            ) { Text("Spend") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AspectEarningsCard(
    aspects: List<Aspect>,
    earnedThisWeek: Map<String, Int>,
    lifetimeEarned: Map<String, Int>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (aspects.isEmpty() || (earnedThisWeek.isEmpty() && lifetimeEarned.isEmpty())) {
                Text(
                    "No earnings yet — complete tasks to earn resources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text("Aspect", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("This Week", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(72.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("Lifetime", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(64.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                val allAspectIds = (earnedThisWeek.keys + lifetimeEarned.keys).distinct()
                allAspectIds.forEach { id ->
                    val aspect = aspects.firstOrNull { it.id == id }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(10.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = parseColor(aspect?.color ?: "#6200EE")
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            aspect?.name ?: id,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "+${earnedThisWeek[id] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(72.dp)
                        )
                        Text(
                            "${lifetimeEarned[id] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.width(64.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun MappingDialog(
    resource: GameResource,
    currentMappings: List<GameResourceMapping>,
    aspects: List<com.lifeops.app.data.model.Aspect>,
    onAdd: (String, Float) -> Unit,
    onDelete: (GameResourceMapping) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedAspectId by remember { mutableStateOf(aspects.firstOrNull()?.id ?: "") }
    var weight by remember { mutableStateOf("1.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map Aspects → ${resource.name}") },
        text = {
            Column {
                currentMappings.forEach { mapping ->
                    val aspectName = aspects.firstOrNull { it.id == mapping.aspectId }?.name ?: mapping.aspectId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$aspectName ×${mapping.weight}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDelete(mapping) }) { Text("Remove") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Add mapping", style = MaterialTheme.typography.labelMedium)
                var expanded by remember { mutableStateOf(false) }
                val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "Select aspect",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; expanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val w = weight.toFloatOrNull() ?: 1f
                if (selectedAspectId.isNotEmpty()) onAdd(selectedAspectId, w)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
