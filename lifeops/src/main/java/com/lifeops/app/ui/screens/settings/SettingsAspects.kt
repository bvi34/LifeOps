@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.ui.theme.parseColor
import com.operations.suite.ui.pickers.SuiteColorField

/**
 * Editing the aspects and categories work is filed under — the spine everything else in
 * LifeOps hangs off, which is why renaming one is a dialog rather than an inline edit.
 */

@Composable
internal fun AspectItem(
    aspect: Aspect,
    categories: List<com.lifeops.app.data.model.Category>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    onEdit: () -> Unit,
    onAddCategory: () -> Unit,
    onArchiveCategory: (String, Boolean) -> Unit,
    onEditCategory: (com.lifeops.app.data.model.Category) -> Unit
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
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit aspect",
                        modifier = Modifier.size(18.dp)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "• ${cat.name}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = if (cat.isArchived) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { onEditCategory(cat) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit category",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(
                            onClick = { onArchiveCategory(cat.id, !cat.isArchived) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                if (cat.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (cat.isArchived) "Unarchive" else "Archive",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
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
internal fun NewAspectDialog(suggestedColor: String, onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(suggestedColor) }
    var icon by remember { mutableStateOf("star") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                ColorSwatchPicker(selectedColor = color, onSelect = { color = it })
                SuiteColorField(label = "Custom color", color = color, onColorChange = { color = it })
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
internal fun EditAspectDialog(aspect: Aspect, onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(aspect.id) { mutableStateOf(aspect.name) }
    var color by remember(aspect.id) { mutableStateOf(aspect.color) }
    var icon by remember(aspect.id) { mutableStateOf(aspect.icon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Aspect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                ColorSwatchPicker(selectedColor = color, onSelect = { color = it })
                SuiteColorField(label = "Custom color", color = color, onColorChange = { color = it })
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), color, icon) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun NewCategoryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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

@Composable
internal fun EditCategoryDialog(
    category: com.lifeops.app.data.model.Category,
    aspects: List<Aspect>,
    onConfirm: (name: String, aspectId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var selectedAspectId by remember(category.id) { mutableStateOf(category.aspectId) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "Select aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; dropdownExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedAspectId) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
