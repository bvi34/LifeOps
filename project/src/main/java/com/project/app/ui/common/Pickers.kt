package com.project.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.app.data.model.Doc
import com.project.app.logic.OutlineRow
import com.project.app.logic.TreeRow

/**
 * The "point this at something else in the project" dialogs.
 *
 * Three sections need them — a document filed under a scene, a card that is the work for one, a
 * timeline event that happens in one — and the interesting part is the same each time: show the tree
 * with its indentation intact, mark what is currently chosen, and always offer **Not linked**, since
 * un-linking has to be as easy as linking or the link becomes a trap.
 */

/** Pick a piece of the outline, or nothing. */
@Composable
fun OutlinePickerDialog(
    title: String,
    rows: List<OutlineRow>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (rows.isEmpty()) {
                Text("There is nothing in the outline to link to yet.")
            } else {
                LazyColumn {
                    item(key = "none") {
                        PickerRow(
                            label = "Not linked",
                            selected = selectedId == null,
                            indent = 0,
                            onClick = { onPick(null) }
                        )
                    }
                    items(rows, key = { it.node.id }) { row ->
                        PickerRow(
                            label = "${row.number}  ${row.node.title}",
                            selected = row.node.id == selectedId,
                            indent = row.depth,
                            onClick = { onPick(row.node.id) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * Pick a document, or nothing.
 *
 * [excluded] is how a document is kept from being filed inside itself: the caller passes the
 * document and everything under it, and those rows are simply not offered. Refusing the move
 * afterwards would be correct too, but a picker that never shows an impossible choice needs no error
 * message.
 */
@Composable
fun DocPickerDialog(
    title: String,
    rows: List<TreeRow<Doc>>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    noneLabel: String = "Not linked",
    excluded: Set<String> = emptySet()
) {
    val offered = rows.filterNot { it.item.id in excluded }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                item(key = "none") {
                    PickerRow(
                        label = noneLabel,
                        selected = selectedId == null,
                        indent = 0,
                        onClick = { onPick(null) }
                    )
                }
                if (offered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing else to choose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(offered, key = { it.item.id }) { row ->
                    PickerRow(
                        label = row.item.title,
                        selected = row.item.id == selectedId,
                        indent = row.depth,
                        onClick = { onPick(row.item.id) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, indent: Int, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Indentation is capped so a deeply nested row never squeezes its own title away.
            .padding(start = (indent.coerceAtMost(4) * 12).dp, top = 10.dp, bottom = 10.dp)
    )
}
