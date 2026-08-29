package com.project.app.ui.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.Outline
import com.project.app.logic.OutlineRow
import com.project.app.logic.Timeline
import com.project.app.logic.TimelineEvent
import com.project.app.logic.TimelineRow
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.OutlinePickerDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimelineViewModel(
    private val repo: ProjectRepository,
    private val projectId: String
) : ViewModel() {

    private val events = repo.observeTimeline(projectId)

    val rows: StateFlow<List<TimelineRow>> =
        events.map { Timeline.rows(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the "sort by when" offer is worth showing at all. */
    val canAutoSort: StateFlow<Boolean> =
        events.map { Timeline.canAutoSort(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The outline, so an event can be told which piece of the work it happens in. */
    val outline: StateFlow<List<OutlineRow>> =
        repo.observeOutline(projectId)
            .map { Outline.flatten(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(title: String, whenLabel: String?, era: String?, detail: String?, outlineNodeId: String?) =
        viewModelScope.launch { repo.addEvent(projectId, title, whenLabel, era, detail, outlineNodeId) }

    fun update(event: TimelineEvent) = viewModelScope.launch { repo.updateEvent(projectId, event) }

    fun move(id: String, delta: Int) = viewModelScope.launch { repo.moveEvent(projectId, id, delta) }

    fun delete(id: String) = viewModelScope.launch { repo.deleteEvent(projectId, id) }

    fun autoSort() = viewModelScope.launch { repo.autoSortTimeline(projectId) }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimelineViewModel(repo, projectId) as T
    }
}

/**
 * The timeline: what happens, in what order, and whether the dates agree with it.
 *
 * The order on this screen is *the author's*, always. A "when" is free text — "Tuesday", "Year 412
 * of the Concord", "Day 3", "the night before the coronation" — and the app reads a number out of
 * it where it can. What it does with that number is not to rearrange the list but to point at the
 * places where the label disagrees with the order: you put the coronation before the battle, and
 * dated it after. That is the error a project timeline exists to catch, and it is only catchable
 * because the two facts are kept apart.
 *
 * Sorting by the labels is offered as a button, and only when every label reads on one scale. It is
 * an offer because the author may well be right and the label a typo.
 */
@Composable
fun TimelineScreen(vm: TimelineViewModel) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val canSort by vm.canAutoSort.collectAsStateWithLifecycle()
    val outline by vm.outline.collectAsStateWithLifecycle()

    val outlineTitles = remember(outline) {
        outline.associate { it.node.id to "${it.number} ${it.node.title}" }
    }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimelineEvent?>(null) }

    val contradictions = rows.count { it.contradictsOrder }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add event") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canSort || contradictions > 0) {
                item(key = "banner") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (contradictions > 0) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Warning, contentDescription = null)
                                    Text(
                                        "$contradictions ${if (contradictions == 1) "event is" else "events are"} " +
                                            "dated before the event above them.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            if (canSort) {
                                TextButton(onClick = { vm.autoSort() }) {
                                    Icon(Icons.Filled.Sort, contentDescription = null)
                                    Text("  Reorder by when")
                                }
                            }
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nothing on the timeline",
                        detail = "Events go in the order you put them. The “when” can be a date, a " +
                            "year, a day number — or something vague; it is read where it can be."
                    )
                }
            }

            items(rows, key = { it.event.id }) { row ->
                if (row.startsEra) {
                    Text(
                        row.event.era?.takeIf { it.isNotBlank() } ?: "No era",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                EventCard(
                    row = row,
                    linkedTitle = row.event.outlineNodeId?.let { outlineTitles[it] },
                    onEdit = { editing = row.event },
                    onMove = { delta -> vm.move(row.event.id, delta) },
                    onDelete = { vm.delete(row.event.id) }
                )
            }
        }
    }

    if (adding) {
        EventDialog(
            title = "New event",
            event = null,
            outlineRows = outline,
            outlineTitles = outlineTitles,
            onDismiss = { adding = false },
            onSave = { title, whenLabel, era, detail, nodeId ->
                vm.add(title, whenLabel, era, detail, nodeId)
                adding = false
            }
        )
    }

    editing?.let { event ->
        EventDialog(
            title = "Edit event",
            event = event,
            outlineRows = outline,
            outlineTitles = outlineTitles,
            onDismiss = { editing = null },
            onSave = { title, whenLabel, era, detail, nodeId ->
                vm.update(
                    event.copy(
                        title = title,
                        whenLabel = whenLabel,
                        era = era,
                        detail = detail,
                        outlineNodeId = nodeId
                    )
                )
                editing = null
            }
        )
    }
}

@Composable
private fun EventCard(
    row: TimelineRow,
    linkedTitle: String?,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = if (row.contradictsOrder) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.event.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    row.event.whenLabel?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { onMove(-1) }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move earlier")
                }
                IconButton(onClick = { onMove(1) }) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move later")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Event actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit…") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            row.event.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            linkedTitle?.let {
                Text(
                    "Happens in $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // The gap only means something between two labels on the same scale, which is why it is
            // rendered from the row rather than computed here.
            row.gapFromPrevious?.takeIf { it != 0L }?.let { gap ->
                val scale = row.parsed?.scale
                Text(
                    if (gap > 0) "$gap ${scale?.unit ?: "later"} after the one above"
                    else "${-gap} ${scale?.unit ?: "earlier"} before the one above",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (row.contradictsOrder) {
                Text(
                    "Dated before the event above it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun EventDialog(
    title: String,
    event: TimelineEvent?,
    outlineRows: List<OutlineRow>,
    outlineTitles: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, String?, String?) -> Unit
) {
    val key = event?.id ?: "new"
    var name by remember(key) { mutableStateOf(event?.title.orEmpty()) }
    var whenLabel by remember(key) { mutableStateOf(event?.whenLabel.orEmpty()) }
    var era by remember(key) { mutableStateOf(event?.era.orEmpty()) }
    var detail by remember(key) { mutableStateOf(event?.detail.orEmpty()) }
    var outlineNodeId by remember(key) { mutableStateOf(event?.outlineNodeId) }
    var picking by remember { mutableStateOf(false) }

    val parsed = Timeline.parseWhen(whenLabel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What happens") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = whenLabel,
                    onValueChange = { whenLabel = it },
                    label = { Text("When (anything you like)") },
                    singleLine = true,
                    supportingText = {
                        // Say what was understood, rather than rejecting what wasn't.
                        Text(
                            parsed?.let { "Read as ${it.scale.label.lowercase()} ${it.value}" }
                                ?: "Not read as a date — it still keeps its place in the order."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = era,
                    onValueChange = { era = it },
                    label = { Text("Era or arc (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("Detail (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                // Which scene this happens in. The timeline is what the story does; the outline is
                // where it is told — and they are not the same order, which is the whole point of
                // being able to say so.
                TextButton(onClick = { picking = true }) {
                    Text(
                        outlineNodeId?.let { "Happens in ${outlineTitles[it] ?: "something no longer there"}" }
                            ?: "Happens in… (optional)"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(
                    name,
                    whenLabel.ifBlank { null },
                    era.ifBlank { null },
                    detail.ifBlank { null },
                    outlineNodeId
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (picking) {
        OutlinePickerDialog(
            title = "Happens in",
            rows = outlineRows,
            selectedId = outlineNodeId,
            onDismiss = { picking = false },
            onPick = {
                outlineNodeId = it
                picking = false
            }
        )
    }
}
