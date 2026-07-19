@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.lifeops.app.data.model.ActivityTemplate
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.OutdoorRating
import com.lifeops.app.util.WeatherCard

@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAddLocation by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<Task?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.radarUrl) {
        state.radarUrl?.let { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            viewModel.clearRadarUrl()
        }
    }

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else if (state.selectedLocationId != null) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Add location") },
                                onClick = { menuOpen = false; showAddLocation = true }
                            )
                            if (state.selectedLocationId != null) {
                                DropdownMenuItem(
                                    text = { Text("View radar") },
                                    onClick = { menuOpen = false; viewModel.openRadar() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete this location") },
                                    onClick = {
                                        menuOpen = false
                                        state.selectedLocationId?.let { viewModel.deleteLocation(it) }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.locations.isEmpty()) {
                item { EmptyState(onAdd = { showAddLocation = true }) }
                return@LazyColumn
            }

            // Location switcher.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.locations.forEach { loc ->
                        FilterChip(
                            selected = loc.id == state.selectedLocationId,
                            onClick = { viewModel.selectLocation(loc.id) },
                            label = { Text(loc.name.ifBlank { "%.2f, %.2f".format(loc.latitude, loc.longitude) }) }
                        )
                    }
                }
            }

            val report = state.report
            if (report == null) {
                item {
                    Text(
                        "No saved weather yet. Tap refresh to fetch conditions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                item { CurrentConditionsCard(report, state.assessment?.rating) }
                items(state.cards) { card -> WeatherCardView(card) }
            }

            // Per-task weather requirements.
            item {
                Spacer(Modifier.height(4.dp))
                Text("Task weather needs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (state.weekTasks.isEmpty()) {
                item {
                    Text(
                        "No tasks this week.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                items(state.weekTasks, key = { it.id }) { task ->
                    TaskRequirementRow(
                        task = task,
                        requirement = state.requirements[task.id],
                        onClick = { editingTask = task }
                    )
                }
            }
        }
    }

    if (showAddLocation) {
        AddLocationDialog(
            onConfirm = { lat, lon, name -> viewModel.addLocation(lat, lon, name); showAddLocation = false },
            onDismiss = { showAddLocation = false }
        )
    }

    editingTask?.let { task ->
        RequirementDialog(
            task = task,
            existing = state.requirements[task.id],
            templates = state.activityTemplates,
            onSave = { req, appliedTemplateId -> viewModel.setRequirement(req, appliedTemplateId); editingTask = null },
            onDismiss = { editingTask = null }
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Add a location to see conditions, alerts, and the best time for outdoor tasks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add location")
        }
    }
}

@Composable
private fun CurrentConditionsCard(report: WeatherReport, rating: OutdoorRating?) {
    val c = report.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${c.temperatureF}°", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.shortForecast, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Feels like ${c.feelsLikeF}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                rating?.let { RatingBadge(it) }
            }
            Text(
                buildString {
                    append("Wind ${c.wind.speedMph} mph")
                    c.wind.directionCardinal?.let { if (it.isNotBlank()) append(" $it") }
                    c.humidityPct?.let { append("   ·   Humidity $it%") }
                    c.precipitationProbabilityPct?.let { append("   ·   Rain $it%") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun RatingBadge(rating: OutdoorRating) {
    val color = ratingColor(rating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(rating.label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WeatherCardView(card: WeatherCard) {
    when (card) {
        is WeatherCard.Warning -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("⚠ ${card.event}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                if (card.detail.isNotBlank()) {
                    Text(card.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        is WeatherCard.Advisory -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(card.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    card.delayHint?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                if (card.detail.isNotBlank()) {
                    Text(card.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        is WeatherCard.Morning -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today's conditions", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(card.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                card.bestWindowLabel?.let {
                    Text("Best window: $it", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        is WeatherCard.TaskRecommendation -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(card.taskTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${card.matchPercent}%", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Text("Recommended: ${card.windowLabel}", style = MaterialTheme.typography.bodyMedium)
                card.reasons.forEach { reason ->
                    Text("✓ $reason", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun TaskRequirementRow(task: Task, requirement: TaskWeatherRequirement?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                requirementSummary(requirement),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun requirementSummary(req: TaskWeatherRequirement?): String {
    if (req == null || req.isEmpty) return "Tap to set weather needs"
    return buildList {
        if (req.outdoorPreferred) add("Outdoor")
        req.maxTempF?.let { add("≤${it}°") }
        req.minTempF?.let { add("≥${it}°") }
        if (req.avoidRain) add("no rain")
        req.maxWindMph?.let { add("wind≤${it}") }
        req.durationMinutes?.let { add("${it}m") }
    }.joinToString("  ")
}

private fun ratingColor(rating: OutdoorRating): Color = when (rating) {
    OutdoorRating.EXCELLENT -> Color(0xFF2E7D32)
    OutdoorRating.GOOD -> Color(0xFF558B2F)
    OutdoorRating.CAUTION -> Color(0xFFF9A825)
    OutdoorRating.AVOID -> Color(0xFFC62828)
}

@Composable
private fun AddLocationDialog(onConfirm: (Double, Double, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    val latD = lat.toDoubleOrNull()
    val lonD = lon.toDoubleOrNull()
    val valid = latD != null && latD in -90.0..90.0 && lonD != null && lonD in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "US locations only (National Weather Service). The name is optional — it's filled in on refresh.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lat, onValueChange = { lat = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lon, onValueChange = { lon = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (valid) onConfirm(latD!!, lonD!!, name.trim()) }, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RequirementDialog(
    task: Task,
    existing: TaskWeatherRequirement?,
    templates: List<ActivityTemplate>,
    onSave: (TaskWeatherRequirement, appliedTemplateId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var outdoor by remember { mutableStateOf(existing?.outdoorPreferred ?: false) }
    var avoidRain by remember { mutableStateOf(existing?.avoidRain ?: false) }
    var duration by remember { mutableStateOf(existing?.durationMinutes?.toString() ?: "") }
    var maxTemp by remember { mutableStateOf(existing?.maxTempF?.toString() ?: "") }
    var minTemp by remember { mutableStateOf(existing?.minTempF?.toString() ?: "") }
    var maxWind by remember { mutableStateOf(existing?.maxWindMph?.toString() ?: "") }
    var activityMenu by remember { mutableStateOf(false) }
    // Which activity (if any) seeded these values — powers the learning signal on save.
    var appliedTemplateId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title, maxLines = 2) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Prefill everything from a saved activity, then tweak below.
                if (templates.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = activityMenu, onExpandedChange = { activityMenu = it }) {
                        OutlinedTextField(
                            value = "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Start from activity (optional)") },
                            placeholder = { Text("Pick one…") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(activityMenu) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = activityMenu, onDismissRequest = { activityMenu = false }) {
                            templates.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.name) },
                                    onClick = {
                                        outdoor = t.outdoorPreferred
                                        avoidRain = t.avoidRain
                                        duration = t.durationMinutes?.toString() ?: ""
                                        maxTemp = t.maxTempF?.toString() ?: ""
                                        minTemp = t.minTempF?.toString() ?: ""
                                        maxWind = t.maxWindMph?.toString() ?: ""
                                        appliedTemplateId = t.id
                                        activityMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                SwitchRow("Outdoor preferred", outdoor) { outdoor = it }
                SwitchRow("Avoid rain", avoidRain) { avoidRain = it }
                NumField("Max temperature (°F)", maxTemp) { maxTemp = it }
                NumField("Min temperature (°F)", minTemp) { minTemp = it }
                NumField("Max wind (mph)", maxWind) { maxWind = it }
                NumField("Duration (minutes)", duration) { duration = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    TaskWeatherRequirement(
                        taskId = task.id,
                        outdoorPreferred = outdoor,
                        durationMinutes = duration.toIntOrNull(),
                        maxTempF = maxTemp.toIntOrNull(),
                        minTempF = minTemp.toIntOrNull(),
                        avoidRain = avoidRain,
                        maxWindMph = maxWind.toIntOrNull()
                    ),
                    appliedTemplateId
                )
            }) { Text("Save") }
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

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
