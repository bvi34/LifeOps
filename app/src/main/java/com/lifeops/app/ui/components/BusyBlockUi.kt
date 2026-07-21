@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.util.DateUtil
import java.util.UUID

// Index 0..6 maps to bit 0..6 = Monday..Sunday, matching BusyBlocks.occursOn.
private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private const val WEEKDAYS_MASK = 0b0011111  // Mon–Fri
private const val EVERYDAY_MASK = 0b1111111

/** minutes-from-midnight → "HH:mm". */
fun formatClock(minutes: Int): String {
    val h = (minutes / 60).coerceIn(0, 23)
    val m = (minutes % 60).coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

private fun parseClock(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** Human recurrence summary: "Weekdays", "Every day", "Mon, Wed, Fri", or a one-off's date. */
fun busyRecurrenceSummary(block: BusyBlock): String {
    block.specificDate?.let { return DateUtil.formatDate(it) }
    return when (block.daysMask) {
        WEEKDAYS_MASK -> "Weekdays"
        EVERYDAY_MASK -> "Every day"
        else -> DAY_LABELS.filterIndexed { i, _ -> (block.daysMask and (1 shl i)) != 0 }
            .joinToString(", ").ifEmpty { "No days" }
    }
}

/** One row in a schedule list: time range, title, recurrence, and edit/delete actions. */
@Composable
fun BusyBlockRow(block: BusyBlock, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${formatClock(block.startMinutes)}–${formatClock(block.endMinutes)}  ${block.title}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                busyRecurrenceSummary(block),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
    }
}

/**
 * Create/edit dialog for a busy block. [existing] non-null edits in place; [personId] stamps the
 * owner (null = the user's own schedule). [onSave] receives the fully-built block.
 */
@Composable
fun BusyBlockEditorDialog(
    existing: BusyBlock?,
    personId: String?,
    onSave: (BusyBlock) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var startText by remember { mutableStateOf(formatClock(existing?.startMinutes ?: 540)) }   // 09:00
    var endText by remember { mutableStateOf(formatClock(existing?.endMinutes ?: 1020)) }       // 17:00
    var weekly by remember { mutableStateOf(existing?.specificDate == null) }
    var daysMask by remember { mutableStateOf(existing?.takeIf { it.specificDate == null }?.daysMask ?: WEEKDAYS_MASK) }
    var dateText by remember { mutableStateOf(existing?.specificDate ?: "") }

    val start = parseClock(startText)
    val end = parseClock(endText)
    val timesValid = start != null && end != null && start < end
    val recurrenceValid = if (weekly) daysMask != 0 else DateUtil.isValidDate(dateText.trim())
    val canSave = title.isNotBlank() && timesValid && recurrenceValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add busy time" else "Edit busy time") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (e.g. Work, Standup)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Start") },
                        placeholder = { Text("HH:mm") },
                        isError = start == null,
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("End") },
                        placeholder = { Text("HH:mm") },
                        isError = end == null || (start != null && end != null && end <= start),
                        singleLine = true,
                        modifier = Modifier.padding(start = 8.dp).width(120.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(checked = weekly, onCheckedChange = { weekly = it })
                    Text("Repeats weekly", style = MaterialTheme.typography.bodyMedium)
                }

                if (weekly) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_LABELS.forEachIndexed { i, label ->
                            val bit = 1 shl i
                            FilterChip(
                                selected = (daysMask and bit) != 0,
                                onClick = { daysMask = daysMask xor bit },
                                label = { Text(label) }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("Date") },
                        placeholder = { Text("YYYY-MM-DD") },
                        isError = dateText.isNotBlank() && !DateUtil.isValidDate(dateText.trim()),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        BusyBlock(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            startMinutes = start!!,
                            endMinutes = end!!,
                            daysMask = if (weekly) daysMask else 0,
                            specificDate = if (weekly) null else dateText.trim(),
                            personId = personId,
                            createdAt = existing?.createdAt ?: DateUtil.now()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
