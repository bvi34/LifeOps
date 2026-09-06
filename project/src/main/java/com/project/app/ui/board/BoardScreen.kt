package com.project.app.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteNoteField
import com.operations.suite.ui.fields.SuiteNumberField
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.suite.ui.pickers.SuiteDateButton
import com.operations.suite.ui.pickers.SuiteDates
import com.operations.suitekit.SuiteVerdict
import com.project.app.data.model.Doc
import com.project.app.data.repository.ProjectRepository
import com.project.app.logic.Board
import com.project.app.logic.BoardCard
import com.project.app.logic.BoardColumn
import com.project.app.logic.BoardLane
import com.project.app.logic.Due
import com.project.app.logic.DueOpinion
import com.project.app.logic.DueStanding
import com.project.app.logic.DueState
import com.project.app.logic.Outline
import com.project.app.logic.OutlineRow
import com.project.app.logic.ProjectKind
import com.project.app.logic.Tree
import com.project.app.ui.common.DocPickerDialog
import com.project.app.ui.common.EmptyState
import com.project.app.ui.common.OutlinePickerDialog
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the board screen draws: the lanes, anything stranded, and what a card can point at. */
data class BoardState(
    val lanes: List<BoardLane> = emptyList(),
    val orphans: List<BoardCard> = emptyList(),
    val outlineRows: List<OutlineRow> = emptyList(),
    val docs: List<Doc> = emptyList()
) {
    /** Outline titles by id, for the line under a card that names the piece it is work on. */
    val outlineTitles: Map<String, String> =
        outlineRows.associate { it.node.id to "${it.number} ${it.node.title}" }

    val docTitles: Map<String, String> = docs.associate { it.id to it.title }
}

class BoardViewModel(
    private val repo: ProjectRepository,
    private val projectId: String,
    /**
     * Ask for a hand-off round.
     *
     * Called after every edit that could change what the week should hold — a date set or dropped,
     * a card renamed, moved into or out of the finished column, or deleted. The round is idempotent
     * and cheap when there is nothing to do, so this errs towards calling it: reasoning about which
     * edits *cannot* matter is exactly how a card ends up with a stale task on somebody's week.
     */
    private val sync: () -> Unit = {}
) : ViewModel() {

    val state: StateFlow<BoardState> = combine(
        repo.observeColumns(projectId),
        repo.observeCards(projectId),
        repo.observeOutline(projectId),
        repo.observeDocs(projectId)
    ) { columns, cards, outline, docs ->
        BoardState(
            lanes = Board.lanes(columns, cards),
            orphans = Board.orphans(columns, cards),
            outlineRows = Outline.flatten(outline),
            docs = docs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardState())

    fun addCard(columnId: String, title: String) =
        viewModelScope.launch { repo.addCard(projectId, columnId, title) }

    fun updateCard(card: BoardCard) = viewModelScope.launch {
        repo.updateCard(projectId, card)
        sync()
    }

    fun deleteCard(id: String) = viewModelScope.launch {
        // The task goes with the card, and the round works that out for itself: the card is gone,
        // so the next snapshot has nothing asking for it and the link it left behind is retired.
        repo.deleteCard(projectId, id)
        sync()
    }

    fun moveCard(cardId: String, toColumnId: String, toIndex: Int) = viewModelScope.launch {
        repo.moveCard(projectId, cardId, toColumnId, toIndex)
        // Moving into the finished column is how a card is done here, which is the moment its task
        // should come off the week — and moving back out is the moment it should return.
        sync()
    }

    fun addColumn(name: String) = viewModelScope.launch { repo.addColumn(projectId, name) }

    fun updateColumn(column: BoardColumn) = viewModelScope.launch { repo.updateColumn(projectId, column) }

    fun moveColumn(id: String, delta: Int) = viewModelScope.launch { repo.moveColumn(projectId, id, delta) }

    fun deleteColumn(id: String) = viewModelScope.launch { repo.deleteColumn(projectId, id) }

    fun refileOrphans(columnId: String) = viewModelScope.launch { repo.refileOrphans(projectId, columnId) }

    class Factory(
        private val repo: ProjectRepository,
        private val projectId: String,
        private val sync: () -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BoardViewModel(repo, projectId, sync) as T
    }
}

/**
 * Implementation: the board the work actually gets done on.
 *
 * Lanes scroll sideways and each lane scrolls down, which is what makes a kanban board usable on a
 * phone at all. Moving a card is a **menu of destinations**, not a drag: dragging a card between two
 * columns that are half off-screen is a coin toss, and the one thing a board must never be is unsure
 * where it just put your work.
 *
 * WIP limits warn and never refuse. A board that rejects the card in your hand teaches you to lie to
 * it — you park the work somewhere it doesn't belong, and the board stops describing reality, which
 * was the only thing it was for.
 *
 * Deleting a column does not delete its cards. They are stranded on purpose, and the banner at the
 * top is how they are found and re-filed.
 */
@Composable
fun BoardScreen(vm: BoardViewModel, kind: ProjectKind) {
    val state by vm.state.collectAsStateWithLifecycle()

    var addingCardIn by remember { mutableStateOf<String?>(null) }
    var addingColumn by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<BoardCard?>(null) }
    var editingColumn by remember { mutableStateOf<BoardColumn?>(null) }
    var movingCard by remember { mutableStateOf<BoardCard?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addingColumn = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add column") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.orphans.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null)
                            Text(
                                "${state.orphans.size} ${if (state.orphans.size == 1) "card" else "cards"} " +
                                    "lost their column and are not shown in any lane.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.lanes.firstOrNull()?.let { first ->
                            TextButton(onClick = { vm.refileOrphans(first.column.id) }) {
                                Text("Move them into ${first.column.name}")
                            }
                        }
                    }
                }
            }

            if (state.lanes.isEmpty()) {
                EmptyState(
                    title = "No columns",
                    detail = "A board needs somewhere to put things. Add a column to get going."
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.lanes.forEach { lane ->
                    Lane(
                        lane = lane,
                        kind = kind,
                        outlineTitles = state.outlineTitles,
                        docTitles = state.docTitles,
                        onAddCard = { addingCardIn = lane.column.id },
                        onOpenCard = { editingCard = it },
                        onMoveCard = { movingCard = it },
                        onEditColumn = { editingColumn = lane.column },
                        onMoveColumn = { delta -> vm.moveColumn(lane.column.id, delta) },
                        onDeleteColumn = { vm.deleteColumn(lane.column.id) }
                    )
                }
            }
        }
    }

    addingCardIn?.let { columnId ->
        TextPromptDialog(
            title = "New card",
            label = "What needs doing",
            onDismiss = { addingCardIn = null },
            onConfirm = {
                vm.addCard(columnId, it)
                addingCardIn = null
            }
        )
    }

    if (addingColumn) {
        TextPromptDialog(
            title = "New column",
            label = "Column name",
            onDismiss = { addingColumn = false },
            onConfirm = {
                vm.addColumn(it)
                addingColumn = false
            }
        )
    }

    editingCard?.let { card ->
        CardDialog(
            card = card,
            kind = kind,
            outlineRows = state.outlineRows,
            docs = state.docs,
            outlineTitles = state.outlineTitles,
            docTitles = state.docTitles,
            onDismiss = { editingCard = null },
            onSave = {
                vm.updateCard(it)
                editingCard = null
            },
            onDelete = {
                vm.deleteCard(card.id)
                editingCard = null
            }
        )
    }

    editingColumn?.let { column ->
        ColumnDialog(
            column = column,
            onDismiss = { editingColumn = null },
            onSave = {
                vm.updateColumn(it)
                editingColumn = null
            }
        )
    }

    movingCard?.let { card ->
        MoveCardDialog(
            card = card,
            lanes = state.lanes,
            onDismiss = { movingCard = null },
            onPick = { columnId ->
                // Dropped at the end of the destination: the only position a menu can mean without
                // asking a second question nobody wants to answer.
                vm.moveCard(card.id, columnId, Int.MAX_VALUE)
                movingCard = null
            }
        )
    }
}

@Composable
private fun Lane(
    lane: BoardLane,
    kind: ProjectKind,
    outlineTitles: Map<String, String>,
    docTitles: Map<String, String>,
    onAddCard: () -> Unit,
    onOpenCard: (BoardCard) -> Unit,
    onMoveCard: (BoardCard) -> Unit,
    onEditColumn: () -> Unit,
    onMoveColumn: (Int) -> Unit,
    onDeleteColumn: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.width(280.dp).fillMaxHeight().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEditColumn)) {
                Text(
                    lane.column.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildString {
                        append(lane.count)
                        lane.column.wipLimit?.takeIf { !lane.column.isDone }?.let { append(" / $it") }
                        if (lane.isOverLimit) append(" · over limit")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lane.isOverLimit) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Column actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Add card") },
                        onClick = {
                            menuOpen = false
                            onAddCard()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename / set limit…") },
                        onClick = {
                            menuOpen = false
                            onEditColumn()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move left") },
                        onClick = {
                            menuOpen = false
                            onMoveColumn(-1)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move right") },
                        onClick = {
                            menuOpen = false
                            onMoveColumn(1)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete column (keeps its cards)") },
                        onClick = {
                            menuOpen = false
                            onDeleteColumn()
                        }
                    )
                }
            }
        }

        // Read once for the whole lane rather than per card: "today" is one fact, and asking the
        // clock inside a list item lets the same list disagree with itself as it scrolls past
        // midnight.
        val today = remember { LocalDate.now() }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(lane.cards, key = { it.id }) { card ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCard(card) }
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                card.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onMoveCard(card) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Move card"
                                )
                            }
                        }
                        card.notes?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        card.outlineNodeId?.let { nodeId ->
                            Text(
                                outlineTitles[nodeId] ?: "linked ${kind.piece} no longer in the outline",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        card.docId?.let { docId ->
                            Text(
                                docTitles[docId] ?: "linked document no longer here",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Due.standing(
                            dueOn = Due.dateOf(card.dueOn),
                            today = today,
                            // A card in the finished column is not late, however late it was: see
                            // `logic/Due.DueState.DONE`.
                            done = lane.column.isDone
                        )?.let { DueChip(it) }
                    }
                }
            }

            item(key = "add") {
                TextButton(onClick = onAddCard) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add card")
                }
            }
        }
    }
}

/**
 * When a card is due, in one line, coloured by how much that now matters.
 *
 * Colour is the whole reason this is a chip rather than another grey caption: a board is read at a
 * glance and "overdue" has to survive that glance. It is also the *only* thing a due date does to
 * the board — nothing reorders, nothing is hidden, no lane sorts itself. Project says when the work
 * is due and never when you will do it; that decision belongs to LifeOps.
 */
@Composable
private fun DueChip(standing: DueStanding) {
    val colour = when (standing.state) {
        DueState.OVERDUE -> MaterialTheme.colorScheme.error
        DueState.TODAY -> MaterialTheme.colorScheme.tertiary
        DueState.SOON -> MaterialTheme.colorScheme.secondary
        DueState.LATER, DueState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        standing.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (standing.state == DueState.OVERDUE) FontWeight.SemiBold else null,
        color = colour
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SuiteTextField(label = label, value = text, onValueChange = { text = it })
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One card, and what it is work *on*.
 *
 * The two links are the reason the board is in this app rather than in a separate one. A card that
 * points at a scene and at the document being written for it turns "what am I doing this week" into
 * one tap from the thing itself — and it is why the outline can be told which of its pieces are
 * actually in flight.
 */
@Composable
private fun CardDialog(
    card: BoardCard,
    kind: ProjectKind,
    outlineRows: List<OutlineRow>,
    docs: List<Doc>,
    outlineTitles: Map<String, String>,
    docTitles: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (BoardCard) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(card.id) { mutableStateOf(card.title) }
    var notes by remember(card.id) { mutableStateOf(card.notes.orEmpty()) }
    var outlineNodeId by remember(card.id) { mutableStateOf(card.outlineNodeId) }
    var docId by remember(card.id) { mutableStateOf(card.docId) }
    var dueOn by remember(card.id) { mutableStateOf(Due.dateOf(card.dueOn)) }
    var publish by remember(card.id) { mutableStateOf(card.publishToLifeOps) }
    var pickingOutline by remember { mutableStateOf(false) }
    var pickingDoc by remember { mutableStateOf(false) }

    val docTree = remember(docs) {
        Tree.flatten(docs, { it.id }, { it.parentDocId }, compareBy({ it.sortOrder }, { it.title.lowercase() }))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Title", value = title, onValueChange = { title = it })
                SuiteNoteField(
                    label = "Notes",
                    value = notes,
                    onValueChange = { notes = it },
                    minLines = 1,
                    maxLines = 4
                )

                TextButton(onClick = { pickingOutline = true }) {
                    Text(
                        outlineNodeId?.let { "${kind.piece.replaceFirstChar { c -> c.uppercase() }}: " +
                            (outlineTitles[it] ?: "no longer in the outline") }
                            ?: "Link to a ${kind.piece}…"
                    )
                }
                TextButton(onClick = { pickingDoc = true }) {
                    Text(
                        docId?.let { "Document: ${docTitles[it] ?: "no longer here"}" }
                            ?: "Link to a document…"
                    )
                }

                // The suite's one date picker, told what this app makes of a choice rather than
                // deciding for itself — Project refuses no date and remarks on one that has gone
                // (see `logic/Due`), where another app might well refuse the same Tuesday.
                SuiteDateButton(
                    label = "due date",
                    date = dueOn,
                    onDateChange = { dueOn = it },
                    check = { picked ->
                        when (val opinion = Due.opinionOf(picked, LocalDate.now())) {
                            is DueOpinion.Fine -> SuiteVerdict.Fine
                            is DueOpinion.Remark -> SuiteVerdict.Note(opinion.message)
                        }
                    },
                    display = { Due.standing(it, LocalDate.now())?.label ?: SuiteDates.toIso(it) }
                )

                // Offered only once there is a date, because without one there is nothing a week
                // could hold — a switch that does nothing is worse than no switch.
                if (dueOn != null) {
                    TextButton(onClick = { publish = !publish }) {
                        Text(
                            if (publish) "On the LifeOps week ✓"
                            else "Not on the LifeOps week"
                        )
                    }
                }

                TextButton(onClick = onDelete) { Text("Delete card") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    card.copy(
                        title = title,
                        notes = notes.ifBlank { null },
                        outlineNodeId = outlineNodeId,
                        docId = docId,
                        dueOn = dueOn?.toEpochDay(),
                        publishToLifeOps = publish
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingOutline) {
        OutlinePickerDialog(
            title = "Work on which ${kind.piece}?",
            rows = outlineRows,
            selectedId = outlineNodeId,
            onDismiss = { pickingOutline = false },
            onPick = {
                outlineNodeId = it
                pickingOutline = false
            }
        )
    }

    if (pickingDoc) {
        DocPickerDialog(
            title = "Which document?",
            rows = docTree,
            selectedId = docId,
            onDismiss = { pickingDoc = false },
            onPick = {
                docId = it
                pickingDoc = false
            }
        )
    }
}

@Composable
private fun ColumnDialog(
    column: BoardColumn,
    onDismiss: () -> Unit,
    onSave: (BoardColumn) -> Unit
) {
    var name by remember(column.id) { mutableStateOf(column.name) }
    var limit by remember(column.id) { mutableStateOf(column.wipLimit?.toString().orEmpty()) }
    var isDone by remember(column.id) { mutableStateOf(column.isDone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Column") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                SuiteNumberField(
                    // A limit is a count, so the field is one: a WIP limit of "3 or 4" was never going to be
                    // stored, and a keyboard that cannot type it is kinder than an error afterwards.
                    label = "Limit at a time (blank for none)",
                    value = limit,
                    onValueChange = { limit = it },
                    supporting = "A limit warns. It never stops you putting a card here.",
                    // The finished column has no limit to set — work is allowed to pile up in Done.
                    enabled = !isDone
                )
                TextButton(onClick = { isDone = !isDone }) {
                    Text(if (isDone) "This is the finished column ✓" else "Mark as the finished column")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(column.copy(name = name, wipLimit = limit.toIntOrNull(), isDone = isDone))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MoveCardDialog(
    card: BoardCard,
    lanes: List<BoardLane>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${card.title}”") },
        text = {
            LazyColumn {
                items(lanes, key = { it.column.id }) { lane ->
                    TextButton(
                        enabled = lane.column.id != card.columnId,
                        onClick = { onPick(lane.column.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            buildString {
                                append(lane.column.name)
                                lane.column.wipLimit?.takeIf { !lane.column.isDone }?.let {
                                    append(" (${lane.count}/$it)")
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
