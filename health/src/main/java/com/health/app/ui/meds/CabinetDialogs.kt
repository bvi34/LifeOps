package com.health.app.ui.meds

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.Medication
import com.health.app.logic.DoseReminder
import com.health.app.logic.OpenFdaParser
import com.health.app.logic.ReminderMode
import com.health.app.ui.common.ChoiceRow
import com.health.app.ui.common.DecimalField
import com.health.app.ui.common.DisclaimerText
import com.health.app.ui.common.formatStamp

/**
 * The cabinet's own dialogs: editing a bottle, restocking one, setting a reminder, and reading the
 * label Health cached for a product.
 *
 * All four are deliberately plain forms. The interesting thinking in this feature is in `logic/` and
 * in what the app declines to do; the screens are meant to be boring, and a household medicine
 * cabinet is not the place for a novel interaction.
 */

/** Edit everything about a bottle except how much of it has been used. */
@Composable
fun CabinetItemDialog(
    item: CabinetItem,
    onDismiss: () -> Unit,
    onConfirm: (CabinetItem) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var strength by remember { mutableStateOf(item.strength.orEmpty()) }
    var form by remember { mutableStateOf(item.form.orEmpty()) }
    var quantity by remember { mutableStateOf(item.quantity?.let { trimNumber(it) }.orEmpty()) }
    var quantityUnit by remember { mutableStateOf(item.quantityUnit) }
    var expiry by remember { mutableStateOf(item.expiryDate.orEmpty()) }
    var location by remember { mutableStateOf(item.location.orEmpty()) }
    var lowStock by remember { mutableStateOf(item.lowStockThreshold?.let { trimNumber(it) }.orEmpty()) }
    var note by remember { mutableStateOf(item.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${item.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = strength,
                        onValueChange = { strength = it },
                        label = { Text("Strength") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = form,
                        onValueChange = { form = it },
                        label = { Text("Form") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(quantity, { quantity = it }, "Amount left", Modifier.weight(1f))
                    OutlinedTextField(
                        value = quantityUnit,
                        onValueChange = { quantityUnit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text("Expires (YYYY-MM or YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Where it lives") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DecimalField(
                    value = lowStock,
                    onValueChange = { lowStock = it },
                    label = "Tell me it's low at",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        item.copy(
                            name = name.trim(),
                            strength = strength.ifBlank { null },
                            form = form.ifBlank { null },
                            quantity = decimal(quantity),
                            quantityUnit = quantityUnit.trim(),
                            expiryDate = expiry.ifBlank { null },
                            location = location.ifBlank { null },
                            lowStockThreshold = decimal(lowStock),
                            note = note.ifBlank { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A new bottle of the same thing.
 *
 * Sets the amount outright rather than adding to what was left, and takes the new box's expiry date,
 * because that is what actually happened: the old bottle is gone and this is a different one. Adding
 * would carry the old bottle's last 20 mL into a bottle they are not in.
 */
@Composable
fun RestockDialog(
    item: CabinetItem,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Double?, expiryDate: String?) -> Unit
) {
    var quantity by remember { mutableStateOf(item.quantity?.let { trimNumber(it) }.orEmpty()) }
    var expiry by remember { mutableStateOf(item.expiryDate.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restock ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A new pack replaces the old one — the amount and the expiry date are the new " +
                        "box's, not the last one's.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(quantity, { quantity = it }, "Amount", Modifier.weight(1f))
                    Text(
                        item.quantityUnit,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically)
                    )
                }
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text("Expires (YYYY-MM or YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(decimal(quantity), expiry.ifBlank { null }) }
            ) { Text("Restocked") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * How a medicine nudges, if at all.
 *
 * The two modes are the two ways households take medicines — see `logic/DoseReminder`. Set times for
 * the regular ones, when-due for the as-needed ones; and nothing at all is the default, because an
 * app that starts notifying about medicines nobody asked it to notify about gets its notifications
 * turned off wholesale.
 */
@Composable
fun ReminderDialog(
    medication: Medication,
    onDismiss: () -> Unit,
    onConfirm: (ReminderMode, List<java.time.LocalTime>) -> Unit
) {
    var mode by remember { mutableStateOf(medication.reminderMode) }
    var times by remember {
        mutableStateOf(
            medication.reminderTimes
                .takeIf { it.isNotEmpty() }
                ?.let { DoseReminder.formatTimes(it) }
                ?: DoseReminder.formatTimes(DoseReminder.SUGGESTED_TIMES.take(2))
        )
    }

    val parsed = DoseReminder.parseTimes(times)
    val timesInvalid = mode == ReminderMode.FIXED_TIMES && parsed.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me about ${medication.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceRow(
                    options = ReminderMode.entries,
                    selected = mode,
                    onSelect = { mode = it },
                    label = { it.label }
                )

                when (mode) {
                    ReminderMode.OFF -> Text(
                        "Nothing will be sent. The dose window on the card still tells you where " +
                            "things stand whenever you look.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ReminderMode.FIXED_TIMES -> {
                        OutlinedTextField(
                            value = times,
                            onValueChange = { times = it },
                            label = { Text("Times, 24-hour, comma separated") },
                            singleLine = true,
                            isError = timesInvalid,
                            supportingText = {
                                Text(
                                    if (timesInvalid) {
                                        "Write them like 08:00, 20:00."
                                    } else {
                                        "Every day at ${DoseReminder.describeTimes(parsed)}."
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "For the regular ones — the tablet that goes with breakfast, whether " +
                                "or not the last one was late.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ReminderMode.WHEN_DUE -> Text(
                        "For the as-needed ones. Health nudges when the spacing you typed in has " +
                            "elapsed since the last recorded dose — and stays quiet when there " +
                            "hasn't been one, rather than nagging about a medicine nobody is taking.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!medication.active && mode != ReminderMode.OFF) {
                    Text(
                        "${medication.name} is paused, so nothing will be sent until it's resumed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                DisclaimerText()
            }
        },
        confirmButton = {
            TextButton(
                enabled = !timesInvalid,
                onClick = { onConfirm(mode, parsed) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The label, as the manufacturer wrote it.
 *
 * Sections in the order the questions get asked — what it's for, how much, then everything that
 * starts with "don't" — with the source and both dates at the bottom: when Health looked it up, and
 * when the manufacturer last revised it. Those answer different questions about how much to trust
 * what you're reading, so both are shown.
 *
 * Nothing here is summarised, ranked or filtered for relevance. It is a reference you would
 * otherwise be reading off a box in bad light.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonographSheet(
    entry: CabinetEntry,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val monograph = entry.monograph

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                monograph?.displayName ?: entry.item.displayName,
                style = MaterialTheme.typography.titleLarge
            )
            monograph?.descriptor?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (monograph == null) {
                Text(
                    "Health hasn't looked this one up yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                monograph.ingredients.takeIf { it.isNotEmpty() }?.let {
                    LabelBlock("Contains", it.joinToString(", "))
                }
                monograph.manufacturer?.let { LabelBlock("Made by", it) }
                monograph.schedule?.let { LabelBlock("Controlled", it) }

                if (!monograph.hasLabel) {
                    Text(
                        "No openFDA label matched this product. That's common — plenty of real " +
                            "medicines have a sparse entry — and what RxNorm knows is above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                monograph.sections.forEach { section ->
                    LabelBlock(section.title, section.text)
                }

                HorizontalDivider()

                Text(
                    monograph.attribution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OpenFdaParser.formatEffectiveTime(monograph.labelEffectiveTime)?.let {
                    Text(
                        "Label revised $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (monograph.fetchedAt > 0) {
                    Text(
                        "Looked up ${formatStamp(monograph.fetchedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (entry.item.rxcui != null) {
                OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(if (refreshing) "Looking it up…" else "Look it up again")
                }
            }

            DisclaimerText()
        }
    }
}

@Composable
private fun LabelBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun decimal(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

private fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
