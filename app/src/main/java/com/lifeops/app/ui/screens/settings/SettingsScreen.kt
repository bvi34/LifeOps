package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.ui.theme.parseColor

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aspects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::showNewAspectDialog) {
                        Icon(Icons.Default.Add, contentDescription = "Add aspect")
                    }
                }
            }
            items(state.aspects, key = { it.id }) { aspect ->
                AspectItem(
                    aspect = aspect,
                    categories = state.categories[aspect.id] ?: emptyList(),
                    isExpanded = state.expandedAspectId == aspect.id,
                    onToggle = { viewModel.toggleAspectExpanded(aspect.id) },
                    onArchive = { viewModel.archiveAspect(aspect.id, !aspect.isArchived) },
                    onAddCategory = { viewModel.showNewCategoryDialog(aspect.id) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Game Resource Slots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }
            items(state.gameResources, key = { it.id }) { resource ->
                GameResourceItem(resource = resource, onRename = { viewModel.renameGameResource(resource, it) })
            }
        }
    }

    if (state.showNewAspectDialog) {
        NewAspectDialog(
            onConfirm = { name, color, icon -> viewModel.addAspect(name, color, icon); viewModel.hideNewAspectDialog() },
            onDismiss = viewModel::hideNewAspectDialog
        )
    }

    state.newAspectForCategoryId?.let { aspectId ->
        if (state.showNewCategoryDialog) {
            NewCategoryDialog(
                onConfirm = { name -> viewModel.addCategory(aspectId, name); viewModel.hideNewCategoryDialog() },
                onDismiss = viewModel::hideNewCategoryDialog
            )
        }
    }
}

@Composable
private fun AspectItem(
    aspect: Aspect,
    categories: List<com.lifeops.app.data.model.Category>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    onAddCategory: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(16.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = parseColor(aspect.color)
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    aspect.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    color = if (aspect.isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onArchive, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (aspect.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = "Archive",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (isExpanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                categories.forEach { cat ->
                    Text("• ${cat.name}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp),
                        color = if (cat.isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = onAddCategory, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Category")
                }
            }
        }
    }
}

@Composable
private fun GameResourceItem(resource: GameResource, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var nameValue by remember(resource.name) { mutableStateOf(resource.name) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Slot ${resource.slotIndex + 1}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.width(48.dp))
            if (editing) {
                OutlinedTextField(
                    value = nameValue,
                    onValueChange = { nameValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                TextButton(onClick = { onRename(nameValue); editing = false }) { Text("Save") }
            } else {
                Text(resource.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NewAspectDialog(onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#6200EE") }
    var icon by remember { mutableStateOf("star") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Color (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name, color, icon) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewCategoryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
