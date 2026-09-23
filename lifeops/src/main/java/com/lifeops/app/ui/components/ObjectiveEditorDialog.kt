@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.data.repository.ObjectiveStepDraft
import com.operations.suite.ui.pickers.SuiteDateButton
import java.util.UUID

/** How a step opens, as the editor offers it: one choice per step. */
private enum class StepOpening(val label: String) { NOW("Now"), ON_DATE("On a date"), AFTER_PREVIOUS("After previous") }

/** One step row in the editor. [key] is stable across reorders; [id] is the saved step's, if any. */
private data class StepRow(
    val key: String = UUID.randomUUID().toString(),
    val id: String? = null,
    val title: String = "",
    val opening: StepOpening = StepOpening.NOW,
    val opensOn: String? = null,
    val dueDate: String? = null
)

const val DEFAULT_SUCCESS_CRITERIA = "Success reported"

/**
 * Create or edit an Objective: its title, aspect and due date, what counts as success, and the
 * ordered steps that lead there — each opening now, on a date, or once the step before it is done,
 * with an optional due date of its own.
 */
@Composable
fun ObjectiveEditorDialog(
    editing: ObjectiveWithSteps?,
    aspects: List<Aspect>,
    onSave: (
        title: String,
        aspectId: String?,
        dueDate: String,
        successCriteria: String,
        steps: List<ObjectiveStepDraft>
    ) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val objective = editing?.objective
    var title by remember { mutableStateOf(objective?.title ?: "") }
    var aspectId by remember { mutableStateOf(objective?.aspectId) }
    var dueDate by remember { mutableStateOf(objective?.dueDate) }
    var criteria by remember { mutableStateOf(objective?.successCriteria ?: DEFAULT_SUCCESS_CRITERIA) }
    val steps = remember {
        mutableStateListOf<StepRow>().apply {
            editing?.steps?.forEach { s ->
                add(
                    StepRow(
                        id = s.id,
                        title = s.title,
                        opening = when {
                            s.afterPrevious -> StepOpening.AFTER_PREVIOUS
                            s.opensOn != null -> StepOpening.ON_DATE
                            else -> StepOpening.NOW
                        },
                        opensOn = s.opensOn,
                        dueDate = s.dueDate
                    )
                )
            }
        }
    }
    var aspectExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val canSave = title.isNotBlank() && dueDate != null

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (editing == null) "New Objective" else "Edit Objective") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Objective") },
                    placeholder = { Text("e.g. Obtain ITIL 4 Foundation cert") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val selectedAspect = aspects.firstOrNull { it.id == aspectId }
                ExposedDropdownMenuBox(expanded = aspectExpanded, onExpandedChange = { aspectExpanded = it }) {
                    OutlinedTextField(
                        value = selectedAspect?.name ?: "No aspect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Aspect — shown above it each week") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(aspectExpanded) }
                    )
                    ExposedDropdownMenu(expanded = aspectExpanded, onDismissRequest = { aspectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("No aspect") },
                            onClick = { aspectId = null; aspectExpanded = false }
                        )
                        aspects.filter { !it.isArchived || it.id == aspectId }.forEach { aspect ->
                            DropdownMenuItem(
                                text = { Text(aspect.name) },
                                onClick = { aspectId = aspect.id; aspectExpanded = false }
                            )
                        }
                    }
                }

                SuiteDateButton(
                    label = "due date",
                    isoDate = dueDate,
                    onIsoDateChange = { dueDate = it },
                    modifier = Modifier.fillMaxWidth(),
                    clearable = false
                )

                HorizontalDivider()
                Text("Steps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (steps.isEmpty()) {
                    Text(
                        "Break the objective into steps, each opening now, on a date, or once the one before it is done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                steps.forEachIndexed { index, row ->
                    key(row.key) {
                        StepEditor(
                            number = index + 1,
                            row = row,
                            isFirst = index == 0,
                            isLast = index == steps.lastIndex,
                            onChange = { steps[index] = it },
                            onMoveUp = { steps.add(index - 1, steps.removeAt(index)) },
                            onMoveDown = { steps.add(index + 1, steps.removeAt(index)) },
                            onRemove = { steps.removeAt(index) }
                        )
                    }
                }
                TextButton(
                    onClick = {
                        // A new step after an existing one usually follows it; the first opens now.
                        steps.add(StepRow(opening = if (steps.isEmpty()) StepOpening.NOW else StepOpening.AFTER_PREVIOUS))
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add step")
                }

                HorizontalDivider()
                Text("Complete when", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = criteria,
                    onValueChange = { criteria = it },
                    label = { Text("Success criteria") },
                    supportingText = {
                        Text(
                            buildString {
                                append("Reported once every step is done")
                                dueDate?.let { append(" · due ${com.lifeops.app.util.DateUtil.formatDate(it)}") }
                                append(". It stays on your week until you report success or mark it unsuccessful.")
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete objective") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val drafts = steps.filter { it.title.isNotBlank() }.map { row ->
                        ObjectiveStepDraft(
                            id = row.id,
                            title = row.title.trim(),
                            opensOn = row.opensOn.takeIf { row.opening == StepOpening.ON_DATE },
                            afterPrevious = row.opening == StepOpening.AFTER_PREVIOUS,
                            dueDate = row.dueDate
                        )
                    }
                    onSave(
                        title.trim(), aspectId, dueDate!!,
                        criteria.trim().ifBlank { DEFAULT_SUCCESS_CRITERIA }, drafts
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete objective?") },
            text = {
                Text(
                    "This removes it and its steps with no record kept. To close it out instead, " +
                        "report success or mark it unsuccessful."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StepEditor(
    number: Int,
    row: StepRow,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (StepRow) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Step $number",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move step up")
                }
                IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move step down")
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove step", modifier = Modifier.size(18.dp))
                }
            }
            OutlinedTextField(
                value = row.title,
                onValueChange = { onChange(row.copy(title = it)) },
                placeholder = { Text("e.g. Enroll in training") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Opens", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepOpening.entries.forEach { opening ->
                    // "After previous" means nothing on the first step.
                    if (opening == StepOpening.AFTER_PREVIOUS && isFirst) return@forEach
                    FilterChip(
                        selected = row.opening == opening,
                        onClick = { onChange(row.copy(opening = opening)) },
                        label = { Text(opening.label) }
                    )
                }
            }
            if (row.opening == StepOpening.ON_DATE) {
                SuiteDateButton(
                    label = "opening date",
                    isoDate = row.opensOn,
                    onIsoDateChange = { onChange(row.copy(opensOn = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SuiteDateButton(
                label = "step due date",
                isoDate = row.dueDate,
                onIsoDateChange = { onChange(row.copy(dueDate = it)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
