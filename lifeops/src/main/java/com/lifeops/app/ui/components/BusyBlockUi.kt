@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.lifeops.app.data.model.Person
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.RelationshipImbalance
import java.time.LocalDate
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
fun BusyBlockRow(
    block: BusyBlock,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    allPeople: List<Person> = emptyList()
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${formatClock12h(block.startMinutes)}–${formatClock12h(block.endMinutes)}  ${block.title}",
                style = MaterialTheme.typography.bodyMedium
            )
            val peopleNames = allPeople.filter { it.id in block.peopleIds }.map { it.name }
            val subtitle = buildList {
                add(busyRecurrenceSummary(block))
                if (block.reminderEnabled) add("🔔 Reminder")
                if (peopleNames.isNotEmpty()) add("with " + peopleNames.joinToString(", "))
            }.joinToString("  ·  ")
            Text(
                subtitle,
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
 * owner (null = the user's own schedule). [onSave] receives the fully-built block. [initialDate]
 * (yyyy-MM-dd) seeds a brand-new block as a one-off on that day — set when the user starts the add
 * from a specific date, e.g. tapping a day on the calendar; ignored when [existing] is non-null.
 */
@Composable
fun BusyBlockEditorDialog(
    existing: BusyBlock?,
    personId: String?,
    onSave: (BusyBlock) -> Unit,
    onDismiss: () -> Unit,
    allPeople: List<Person> = emptyList(),
    imbalances: List<RelationshipImbalance> = emptyList(),
    initialDate: String? = null
) {
    val seededDate = initialDate?.takeIf { existing == null && DateUtil.isValidDate(it) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var startMinutes by remember { mutableStateOf(existing?.startMinutes ?: 540) }   // 9:00 AM
    var endMinutes by remember { mutableStateOf(existing?.endMinutes ?: 1020) }      // 5:00 PM
    var weekly by remember { mutableStateOf(existing?.specificDate == null && seededDate == null) }
    // Seeded from a day: if the user flips this to weekly, "every <that weekday>" is the sane default.
    var daysMask by remember {
        mutableStateOf(
            existing?.takeIf { it.specificDate == null }?.daysMask
                ?: seededDate?.let { 1 shl (LocalDate.parse(it).dayOfWeek.value - 1) }
                ?: WEEKDAYS_MASK
        )
    }
    var dateText by remember { mutableStateOf(existing?.specificDate ?: seededDate ?: "") }
    // Reminders are an own-schedule feature only; a person's block never notifies.
    val remindersSupported = personId == null
    var remind by remember { mutableStateOf(existing?.reminderEnabled ?: false) }
    var taggedPeople by remember { mutableStateOf(existing?.peopleIds?.toSet() ?: emptySet()) }

    val timesValid = startMinutes < endMinutes
    val recurrenceValid = if (weekly) daysMask != 0 else DateUtil.isValidDate(dateText.trim())
    val canSave = title.isNotBlank() && timesValid && recurrenceValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    existing != null -> "Edit busy time"
                    seededDate != null -> "Add busy time · ${DateUtil.formatDate(seededDate)}"
                    else -> "Add busy time"
                }
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (e.g. Work, Standup)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    TimePickerButton(
                        label = "Start",
                        minutes = startMinutes,
                        onMinutesSelected = { startMinutes = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    TimePickerButton(
                        label = "End",
                        minutes = endMinutes,
                        onMinutesSelected = { endMinutes = it },
                        isError = !timesValid
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
                    DatePickerButton(
                        label = "date",
                        selectedDateStr = dateText.ifBlank { null },
                        onDateSelected = { dateText = it ?: "" },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (remindersSupported) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(checked = remind, onCheckedChange = { remind = it })
                        Text("Remind me when it starts", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Relationship-balance nudges only make sense while booking something new — once
                // you're editing an existing block the moment to act on "haven't seen them" has
                // already passed for this booking.
                if (existing == null && imbalances.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            imbalances.take(2).forEach { imbalance ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        imbalance.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (imbalance.person.id !in taggedPeople) {
                                        TextButton(onClick = { taggedPeople = taggedPeople + imbalance.person.id }) {
                                            Text("Tag")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (allPeople.isNotEmpty()) {
                    Text(
                        "People",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        allPeople.forEach { person ->
                            FilterChip(
                                selected = person.id in taggedPeople,
                                onClick = {
                                    taggedPeople = if (person.id in taggedPeople) {
                                        taggedPeople - person.id
                                    } else {
                                        taggedPeople + person.id
                                    }
                                },
                                label = { Text(person.name) }
                            )
                        }
                    }
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
                            startMinutes = startMinutes,
                            endMinutes = endMinutes,
                            daysMask = if (weekly) daysMask else 0,
                            specificDate = if (weekly) null else dateText.trim(),
                            personId = personId,
                            createdAt = existing?.createdAt ?: DateUtil.now(),
                            reminderEnabled = remindersSupported && remind,
                            googleEventId = existing?.googleEventId,
                            googleCalendarId = existing?.googleCalendarId,
                            peopleIds = taggedPeople.toList()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
