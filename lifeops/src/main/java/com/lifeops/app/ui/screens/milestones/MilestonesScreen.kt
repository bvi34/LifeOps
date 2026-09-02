@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.milestones

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.Milestone
import com.lifeops.app.data.model.Person
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.operations.suite.ui.pickers.SuiteDateButton
import com.lifeops.app.util.DateUtil

/**
 * The Milestones page (History hub → Milestones): a record of rare, once-in-a-lifetime
 * accomplishments. Each one can attach to an aspect and/or a person and grants its points
 * immediately — an aspect attachment routes those points into that aspect's mapped resources.
 */
@Composable
fun MilestonesScreen(
    viewModel: MilestonesViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New milestone")
            }
        }
    ) { padding ->
        if (state.milestones.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No milestones yet. Tap + to mark an accomplishment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            val aspectsById = state.aspects.associateBy { it.id }
            val peopleById = state.people.associateBy { it.id }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.milestones, key = { it.id }) { milestone ->
                    MilestoneCard(
                        milestone = milestone,
                        aspect = milestone.aspectId?.let { aspectsById[it] },
                        personName = milestone.personId?.let { peopleById[it]?.name },
                        onDelete = { viewModel.delete(milestone) }
                    )
                }
            }
        }
    }

    if (showCreate) {
        MilestoneDialog(
            aspects = state.aspects,
            people = state.people,
            onConfirm = { title, description, points, aspectId, personId, achievedAt ->
                viewModel.create(title, description, points, aspectId, personId, achievedAt)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }
}

@Composable
private fun MilestoneCard(
    milestone: Milestone,
    aspect: Aspect?,
    personName: String?,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    milestone.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                milestone.description?.let { desc ->
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Chips: points, aspect (colour dot + name), person, date.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (milestone.points > 0) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("+${milestone.points} pts") }
                        )
                    }
                    aspect?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(parseAspectColor(it.color))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                it.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(DateUtil.localDateKey(milestone.achievedAt))
                        personName?.let { append("   ·   with $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MilestoneDialog(
    aspects: List<Aspect>,
    people: List<Person>,
    onConfirm: (
        title: String,
        description: String?,
        points: Int,
        aspectId: String?,
        personId: String?,
        achievedAt: String?
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var pointsText by remember { mutableStateOf("") }
    var selectedAspectId by remember { mutableStateOf<String?>(null) }
    var selectedPersonId by remember { mutableStateOf<String?>(null) }
    var achievedAt by remember { mutableStateOf<String?>(DateUtil.todayKey()) }
    var aspectExpanded by remember { mutableStateOf(false) }
    var personExpanded by remember { mutableStateOf(false) }

    val selectedAspect = aspects.firstOrNull { it.id == selectedAspectId }
    val selectedPerson = people.firstOrNull { it.id == selectedPersonId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New milestone") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pointsText,
                    onValueChange = { new -> pointsText = new.filter { it.isDigit() }.take(6) },
                    label = { Text("Points") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            if (selectedAspectId != null)
                                "Granted now, into the aspect's mapped resources."
                            else
                                "Attach an aspect to grant these into its resources."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Aspect picker (optional)
                ExposedDropdownMenuBox(
                    expanded = aspectExpanded,
                    onExpandedChange = { aspectExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "No aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = aspectExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = aspectExpanded,
                        onDismissRequest = { aspectExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { selectedAspectId = null; aspectExpanded = false }
                        )
                        aspects.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { selectedAspectId = aspect.id; aspectExpanded = false }
                            )
                        }
                    }
                }

                // Person picker (optional)
                if (people.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = personExpanded,
                        onExpandedChange = { personExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedPerson?.name ?: "No one",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Person (optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = personExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = personExpanded,
                            onDismissRequest = { personExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("No one") },
                                onClick = { selectedPersonId = null; personExpanded = false }
                            )
                            people.forEach { person ->
                                DropdownMenuItem(
                                    text = { Text(person.name) },
                                    onClick = { selectedPersonId = person.id; personExpanded = false }
                                )
                            }
                        }
                    }
                }

                SuiteDateButton(
                    label = "date",
                    isoDate = achievedAt,
                    onIsoDateChange = { achievedAt = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title.trim(),
                        description.trim().takeIf { it.isNotBlank() },
                        pointsText.toIntOrNull() ?: 0,
                        selectedAspectId,
                        selectedPersonId,
                        achievedAt
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun parseAspectColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.Gray
}
