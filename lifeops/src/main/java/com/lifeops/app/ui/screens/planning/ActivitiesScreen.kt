@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.PreferenceSuggestion
import com.operations.suite.ui.fields.SuiteNumberField

/**
 * Saved activities (Phase 4): the library of reusable weather profiles. Built-ins are seeded but
 * fully editable, and users can build their own from scratch via +. Applied to tasks from the
 * Weather screen's requirement editor.
 */
@Composable
fun ActivitiesScreen(
    viewModel: ActivitiesViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ActivityTemplate?>(null) }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "New activity")
            }
        }
    ) { padding ->
        if (state.templates.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No activities yet. Tap + to build one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.suggestions.isNotEmpty()) {
                    items(state.suggestions, key = { "sugg-${it.activityId}-${it.field}" }) { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            onApply = { viewModel.applySuggestion(suggestion) },
                            onDismiss = { viewModel.dismissSuggestion(suggestion) }
                        )
                    }
                }
                items(state.templates, key = { it.id }) { template ->
                    ActivityCard(
                        template = template,
                        onEdit = { editing = template },
                        onDelete = { viewModel.delete(template) }
                    )
                }
            }
        }
    }

    if (creating) {
        ActivityEditorDialog(
            title = "New activity",
            initial = null,
            onSave = { name, outdoor, duration, maxT, minT, rain, wind ->
                viewModel.create(name, outdoor, duration, maxT, minT, rain, wind)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    editing?.let { template ->
        ActivityEditorDialog(
            title = "Edit activity",
            initial = template,
            onSave = { name, outdoor, duration, maxT, minT, rain, wind ->
                viewModel.save(
                    template.copy(
                        name = name, outdoorPreferred = outdoor, durationMinutes = duration,
                        maxTempF = maxT, minTempF = minT, avoidRain = rain, maxWindMph = wind
                    )
                )
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun SuggestionCard(suggestion: PreferenceSuggestion, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Suggestion", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            Text(suggestion.message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply) { Text("Update to ${suggestion.suggestedValue}") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ActivityCard(template: ActivityTemplate, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (template.isBuiltIn) {
                        Spacer(Modifier.width(8.dp))
                        Text("built-in", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
                Text(
                    activitySummary(template),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

private fun activitySummary(t: ActivityTemplate): String = buildList {
    if (t.outdoorPreferred) add("Outdoor")
    t.maxTempF?.let { add("≤${it}°") }
    t.minTempF?.let { add("≥${it}°") }
    if (t.avoidRain) add("no rain")
    t.maxWindMph?.let { add("wind≤${it}") }
    t.durationMinutes?.let { add("${it}m") }
}.ifEmpty { listOf("No weather limits") }.joinToString("  ")

@Composable
private fun ActivityEditorDialog(
    title: String,
    initial: ActivityTemplate?,
    onSave: (
        name: String, outdoor: Boolean, duration: Int?,
        maxTemp: Int?, minTemp: Int?, avoidRain: Boolean, maxWind: Int?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var outdoor by remember { mutableStateOf(initial?.outdoorPreferred ?: true) }
    var avoidRain by remember { mutableStateOf(initial?.avoidRain ?: false) }
    var duration by remember { mutableStateOf(initial?.durationMinutes?.toString() ?: "") }
    var maxTemp by remember { mutableStateOf(initial?.maxTempF?.toString() ?: "") }
    var minTemp by remember { mutableStateOf(initial?.minTempF?.toString() ?: "") }
    var maxWind by remember { mutableStateOf(initial?.maxWindMph?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("Outdoor preferred", outdoor) { outdoor = it }
                SwitchRow("Avoid rain", avoidRain) { avoidRain = it }
                SuiteNumberField(label = "Max temperature (°F)", value = maxTemp, signed = true, onValueChange = { maxTemp = it })
                SuiteNumberField(label = "Min temperature (°F)", value = minTemp, signed = true, onValueChange = { minTemp = it })
                SuiteNumberField(label = "Max wind (mph)", value = maxWind, signed = true, onValueChange = { maxWind = it })
                SuiteNumberField(label = "Duration (minutes)", value = duration, signed = true, onValueChange = { duration = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(), outdoor, duration.toIntOrNull(),
                        maxTemp.toIntOrNull(), minTemp.toIntOrNull(), avoidRain, maxWind.toIntOrNull()
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

