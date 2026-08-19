@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.BusyBlockEditorDialog
import com.lifeops.app.ui.components.BusyBlockRow
import com.lifeops.app.util.BusyBlocks
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_HEADER_FMT = DateTimeFormatter.ofPattern("EEE MMM d")
private val RANGE_FMT = DateTimeFormatter.ofPattern("MMM d")

/**
 * The Planning-tab calendar: your week as an agenda. Each day lists your busy blocks (which the
 * best-time engine treats as unavailability) and any tasks due that day. Add/edit/delete busy
 * times here; per-person schedules live on each Person's detail screen.
 *
 * Adding works two ways: tapping a day starts a one-off block already dated to that day, while the
 * FAB opens the editor unseeded (defaulting to a weekly block).
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onBack: () -> Unit,
    onOpenSync: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addingBlock by remember { mutableStateOf(false) }
    // Set when the add was started from a specific day, so the editor opens dated to it.
    var addingOnDate by remember { mutableStateOf<LocalDate?>(null) }
    var editingBlock by remember { mutableStateOf<BusyBlock?>(null) }

    val weekStart = state.weekStart
    val weekEnd = weekStart.plusDays(6)
    val today = LocalDate.now()

    Scaffold(
        topBar = {
            AppHeader(
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    IconButton(onClick = onOpenSync) {
                        Icon(Icons.Default.Sync, contentDescription = "Google Calendar sync")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addingOnDate = null; addingBlock = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add busy time")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Week navigator
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = viewModel::previousWeek) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous week")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${weekStart.format(RANGE_FMT)} – ${weekEnd.format(RANGE_FMT)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = viewModel::thisWeek) { Text("Today") }
                }
                IconButton(onClick = viewModel::nextWeek) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next week")
                }
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (offset in 0..6) {
                    val date = weekStart.plusDays(offset.toLong())
                    val dayBlocks = state.blocks.filter { BusyBlocks.occursOn(it, date) }
                        .sortedBy { it.startMinutes }
                    val dayTasks = state.dueTasks.filter { it.dueDate == date.toString() }
                    item(key = date.toString()) {
                        DaySection(
                            date = date,
                            isToday = date == today,
                            blocks = dayBlocks,
                            dueTaskTitles = dayTasks.map { it.title },
                            allPeople = state.allPeople,
                            onEditBlock = { editingBlock = it },
                            onDeleteBlock = { viewModel.deleteBlock(it.id) },
                            onAddOnDay = { addingOnDate = date; addingBlock = true }
                        )
                    }
                }
            }
        }
    }

    if (addingBlock || editingBlock != null) {
        BusyBlockEditorDialog(
            existing = editingBlock,
            personId = null,
            onSave = { block ->
                viewModel.saveBlock(block)
                addingBlock = false; addingOnDate = null; editingBlock = null
            },
            onDismiss = { addingBlock = false; addingOnDate = null; editingBlock = null },
            allPeople = state.allPeople,
            imbalances = state.imbalances,
            initialDate = addingOnDate?.toString()
        )
    }
}

@Composable
private fun DaySection(
    date: LocalDate,
    isToday: Boolean,
    blocks: List<BusyBlock>,
    dueTaskTitles: List<String>,
    allPeople: List<com.lifeops.app.data.model.Person>,
    onEditBlock: (BusyBlock) -> Unit,
    onDeleteBlock: (BusyBlock) -> Unit,
    onAddOnDay: () -> Unit
) {
    val addLabel = "Add busy time on ${date.format(DAY_HEADER_FMT)}"
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // The day header doubles as the add affordance for that date.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAddOnDay).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                date.format(DAY_HEADER_FMT) + if (isToday) "  · Today" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.Add,
                contentDescription = addLabel,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        }
        if (blocks.isEmpty() && dueTaskTitles.isEmpty()) {
            Text(
                "Nothing scheduled — tap to add",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddOnDay)
                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
            )
        } else {
            blocks.forEach { block ->
                BusyBlockRow(
                    block = block,
                    onEdit = { onEditBlock(block) },
                    onDelete = { onDeleteBlock(block) },
                    allPeople = allPeople
                )
            }
            dueTaskTitles.forEach { title ->
                Text(
                    "• $title (due)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    }
}
