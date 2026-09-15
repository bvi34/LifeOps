@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.CostResource
import com.lifeops.app.data.model.RunbookWithSteps

/**
 * The dialogs the task detail body opens: attaching a runbook, logging time by hand, and
 * logging what a task cost.
 */

@Composable
internal fun AttachRunbookDialog(
    runbooks: List<RunbookWithSteps>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Runbook") },
        text = {
            if (runbooks.isEmpty()) {
                Text(
                    "No runbooks yet. Create one in Settings → Runbooks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    runbooks.forEach { rb ->
                        val n = rb.steps.size
                        OutlinedCard(
                            onClick = { onPick(rb.runbook.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rb.runbook.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    "$n step${if (n != 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ManualLogTimeDialog(onConfirm: (Int, String?) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val minutesInt = minutes.toIntOrNull()
    val preview = minutesInt?.takeIf { it > 0 }?.let { m ->
        val h = m / 60
        val rem = m % 60
        when {
            h > 0 && rem > 0 -> "$h hr $rem min"
            h > 0 -> "$h hr"
            else -> "$rem min"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Time Manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() } },
                    label = { Text("Minutes spent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (preview != null) {
                    Text(
                        "= $preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { minutesInt?.let { onConfirm(it, note.trim().ifBlank { null }) } },
                enabled = minutesInt != null && minutesInt > 0
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun LogResourceCostDialog(
    resources: List<CostResource>,
    onConfirm: (resourceId: String, amount: Int, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedResource by remember { mutableStateOf(resources.firstOrNull()) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val amountInt = amount.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Resource Cost") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedResource?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Resource") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        resources.forEach { resource ->
                            DropdownMenuItem(
                                text = { Text(resource.name) },
                                onClick = {
                                    selectedResource = resource
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount used") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val res = selectedResource ?: return@Button
                    val amt = amountInt ?: return@Button
                    onConfirm(res.id, amt, note.trim().ifBlank { null })
                },
                enabled = selectedResource != null && amountInt != null && amountInt > 0
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
