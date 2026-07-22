@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.counters

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Category
import com.lifeops.app.data.model.Counter
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.DatePickerButton
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun CountersScreen(
    viewModel: CountersViewModel,
    onOpenCounter: (String) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Counter?>(null) }
    var backdating by remember { mutableStateOf<Counter?>(null) }

    val categoriesFlat = remember(state.categoriesByAspect) { state.categoriesByAspect.values.flatten() }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New counter")
            }
        }
    ) { padding ->
        if (state.counters.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No counters yet. Tap + to add one.",
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
                item {
                    HabitDashboard(
                        activeHabitCount = state.activeHabitCount,
                        touchedTodayCount = state.touchedTodayCount,
                        totalToday = state.totalToday,
                        bestStreakDays = state.bestStreakDays,
                        last7DayTotals = state.last7DayTotals,
                        habits = state.dashboardHabits,
                        categoryNameFor = { id -> categoriesFlat.firstOrNull { it.id == id }?.name },
                        onIncrement = { viewModel.increment(it) },
                        onOpenCounter = { onOpenCounter(it.id) }
                    )
                }

                item {
                    Text(
                        "All counters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                items(state.counters, key = { it.id }) { counter ->
                    CounterCard(
                        counter = counter,
                        categoryName = categoriesFlat.firstOrNull { it.id == counter.categoryId }?.name,
                        weekCount = state.weeklyTotals[counter.id] ?: 0,
                        totalCount = state.cumulativeTotals[counter.id] ?: 0,
                        last7Days = state.last7DaysByCounter[counter.id] ?: List(7) { 0 },
                        onIncrement = { viewModel.increment(counter) },
                        onOpen = { onOpenCounter(counter.id) },
                        onEdit = { editing = counter },
                        onBackdate = { backdating = counter },
                        onArchiveToggle = { viewModel.setArchived(counter, !counter.isArchived) }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CounterEditorDialog(
            title = "New counter",
            initialName = "",
            initialCategoryId = null,
            initialIsHabit = false,
            initialReminderHour = null,
            aspects = state.aspects,
            categoriesByAspect = state.categoriesByAspect,
            onConfirm = { name, categoryId, isHabit, reminderHour ->
                viewModel.createCounter(name, categoryId, isHabit, reminderHour); showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    editing?.let { counter ->
        CounterEditorDialog(
            title = "Edit counter",
            initialName = counter.name,
            initialCategoryId = counter.categoryId,
            initialIsHabit = counter.isHabit,
            initialReminderHour = counter.reminderHour,
            aspects = state.aspects,
            categoriesByAspect = state.categoriesByAspect,
            onConfirm = { name, categoryId, isHabit, reminderHour ->
                viewModel.saveCounter(counter, name, categoryId, isHabit, reminderHour); editing = null
            },
            onDismiss = { editing = null }
        )
    }

    backdating?.let { counter ->
        BackdateDialog(
            counterName = counter.name,
            onConfirm = { occurredAtMillis, delta -> viewModel.logBackdated(counter, occurredAtMillis, delta); backdating = null },
            onDismiss = { backdating = null }
        )
    }
}


@Composable
private fun HabitDashboard(
    activeHabitCount: Int,
    touchedTodayCount: Int,
    totalToday: Int,
    bestStreakDays: Int,
    last7DayTotals: List<Int>,
    habits: List<CounterDashboardHabit>,
    categoryNameFor: (String?) -> String?,
    onIncrement: (Counter) -> Unit,
    onOpenCounter: (Counter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Habit dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Today you touched $touchedTodayCount of $activeHabitCount active habits.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardMetric("Today", totalToday.toString(), Modifier.weight(1f))
                    DashboardMetric("Active", "$touchedTodayCount/$activeHabitCount", Modifier.weight(1f))
                    DashboardMetric("Best streak", "${bestStreakDays}d", Modifier.weight(1f))
                }
                WeeklySparkline(last7DayTotals, MaterialTheme.colorScheme.primary)
            }
        }

        val attention = habits.filter { it.todayTotal == 0 }.take(3)
        if (attention.isNotEmpty()) {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Still open today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    attention.forEach { habit ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(habit.counter.name, fontWeight = FontWeight.Medium)
                                Text(
                                    listOfNotNull(categoryNameFor(habit.counter.categoryId), "${habit.weekTotal} this week", "${habit.streakDays}d streak").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            FilledTonalButton(onClick = { onIncrement(habit.counter) }) { Text("Log") }
                        }
                    }
                }
            }
        }

        Text("Habit momentum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        habits.take(5).forEach { habit ->
            HabitMomentumRow(habit, categoryNameFor(habit.counter.categoryId), onIncrement, onOpenCounter)
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun HabitMomentumRow(
    habit: CounterDashboardHabit,
    categoryName: String?,
    onIncrement: (Counter) -> Unit,
    onOpenCounter: (Counter) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenCounter(habit.counter) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(habit.counter.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (habit.todayTotal > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("done today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    listOfNotNull(categoryName, "${habit.weekTotal} this week", "${habit.cumulativeTotal} total").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeeklyDots(habit.last7Days)
                    Spacer(Modifier.width(8.dp))
                    Text("${habit.streakDays}d", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
            FilledTonalButton(onClick = { onIncrement(habit.counter) }) { Text("+1") }
        }
    }
}

/** The shared 7-day activity dots: one square per day, oldest first, filled on active days.
 *  Used on both the habit momentum rows and every counter card so patterns read the same way. */
@Composable
private fun WeeklyDots(values: List<Int>, dim: Float = 1f) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { total ->
            val color = if (total > 0) MaterialTheme.colorScheme.primary.copy(alpha = dim)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = dim)
            Box(Modifier.size(18.dp).background(color, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                if (total > 1) Text(total.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun WeeklySparkline(values: List<Int>, color: Color) {
    val maxValue = max(1, values.maxOrNull() ?: 0)
    Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val barWidth = size.width / (values.size * 2f - 1f)
        values.forEachIndexed { index, value ->
            val height = size.height * (value.toFloat() / maxValue.toFloat()).coerceAtLeast(if (value > 0) 0.12f else 0.04f)
            drawRoundRect(
                color = color.copy(alpha = if (value > 0) 0.9f else 0.22f),
                topLeft = androidx.compose.ui.geometry.Offset(index * barWidth * 2f, size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )
        }
    }
}

@Composable
private fun CounterCard(
    counter: Counter,
    categoryName: String?,
    weekCount: Int,
    totalCount: Int,
    last7Days: List<Int>,
    onIncrement: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onBackdate: () -> Unit,
    onArchiveToggle: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dim = if (counter.isArchived) 0.5f else 1f

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        counter.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim)
                    )
                    if (counter.isHabit) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "habit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = dim)
                        )
                    }
                    if (counter.isArchived) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "archived",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Text(
                    buildString {
                        append("This week: $weekCount   ·   Total: $totalCount")
                        if (categoryName != null) append("   ·   $categoryName")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
                WeeklyDots(last7Days, dim = dim)
            }
            if (!counter.isArchived) {
                FilledTonalButton(onClick = onIncrement) { Text("+1") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Log a past day") }, onClick = { menuOpen = false; onBackdate() })
                    DropdownMenuItem(
                        text = { Text(if (counter.isArchived) "Unarchive" else "Archive") },
                        onClick = { menuOpen = false; onArchiveToggle() }
                    )
                }
            }
        }
    }
}

/** Name + aspect/category picker — same two-dropdown pattern projects use. */
@Composable
private fun CounterEditorDialog(
    title: String,
    initialName: String,
    initialCategoryId: String?,
    initialIsHabit: Boolean,
    initialReminderHour: Int?,
    aspects: List<Aspect>,
    categoriesByAspect: Map<String, List<Category>>,
    onConfirm: (name: String, categoryId: String?, isHabit: Boolean, reminderHour: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var isHabit by remember { mutableStateOf(initialIsHabit) }
    var reminderHour by remember { mutableStateOf(initialReminderHour) }
    var showReminderPicker by remember { mutableStateOf(false) }
    // A counter stores only categoryId; derive the owning aspect so the dropdowns prefill.
    val initialAspectId = remember(initialCategoryId, categoriesByAspect) {
        categoriesByAspect.entries.firstOrNull { entry -> entry.value.any { it.id == initialCategoryId } }?.key
    }
    var selectedAspectId by remember { mutableStateOf(initialAspectId) }
    var selectedCategoryId by remember { mutableStateOf(initialCategoryId) }
    var aspectExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
    val categoriesForAspect = categoriesByAspect[selectedAspectId] ?: emptyList()
    val selectedCategory = categoriesForAspect.firstOrNull { it.id == selectedCategoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Habit flag: promotes this counter onto the habit dashboard and unlocks its
                // own daily reminder.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Track as habit", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Show on the habit dashboard with streaks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(checked = isHabit, onCheckedChange = { isHabit = it })
                }
                // Per-habit daily reminder — only meaningful for a habit.
                if (isHabit) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily reminder", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                reminderHour?.let { formatReminderHour(it) } ?: "Off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        if (reminderHour != null) {
                            TextButton(onClick = { reminderHour = null }) { Text("Clear") }
                        }
                        TextButton(onClick = { showReminderPicker = true }) {
                            Text(if (reminderHour == null) "Set" else "Change")
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = aspectExpanded, onExpandedChange = { aspectExpanded = it }) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "No aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = aspectExpanded, onDismissRequest = { aspectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { selectedAspectId = null; selectedCategoryId = null; aspectExpanded = false }
                        )
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; selectedCategoryId = null; aspectExpanded = false }
                            )
                        }
                    }
                }
                if (selectedAspectId != null && categoriesForAspect.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                        OutlinedTextField(
                            value = selectedCategory?.name ?: "No category",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("No category") },
                                onClick = { selectedCategoryId = null; categoryExpanded = false }
                            )
                            categoriesForAspect.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = { selectedCategoryId = cat.id; categoryExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), selectedCategoryId, isHabit, reminderHour) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showReminderPicker) {
        ReminderHourPickerDialog(
            currentHour = reminderHour,
            onSelect = { reminderHour = it; showReminderPicker = false },
            onDismiss = { showReminderPicker = false }
        )
    }
}

/** hour-of-day (0-23) as a friendly "8:00 AM", matching the settings reminder pickers. */
private fun formatReminderHour(hour: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h:00 $suffix"
}

/** Single-hour radio picker for a habit's daily reminder (same shape as the settings dialogs). */
@Composable
private fun ReminderHourPickerDialog(
    currentHour: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder time") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                (5..22).forEach { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(h) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = h == currentHour, onClick = { onSelect(h) })
                        Text(
                            formatReminderHour(h),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Backdate / bulk: pick a past day and how many to add. Interpreted at noon system-zone so
 *  the event's weekKey lands in the right week. */
@Composable
private fun BackdateDialog(
    counterName: String,
    onConfirm: (occurredAtMillis: Long, delta: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var dateStr by remember { mutableStateOf<String?>(null) }
    var deltaText by remember { mutableStateOf("1") }
    val delta = deltaText.toIntOrNull() ?: 0
    val canSave = dateStr != null && delta > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a past day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add to \"$counterName\" on a past day.", style = MaterialTheme.typography.bodySmall)
                DatePickerButton(
                    label = "date",
                    selectedDateStr = dateStr,
                    onDateSelected = { dateStr = it },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = deltaText,
                    onValueChange = { v -> deltaText = v.filter { it.isDigit() } },
                    label = { Text("How many") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val d = dateStr
                    if (d != null && delta > 0) {
                        val millis = LocalDate.parse(d).atTime(12, 0)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onConfirm(millis, delta)
                    }
                },
                enabled = canSave
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
