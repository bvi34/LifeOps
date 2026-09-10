package com.health.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Ask before a delete that cannot be offered back.
 *
 * The other half of the rule [UndoOffers] states: a delete that puts the row back exactly is offered
 * back, and one that takes something else with it — the file behind a document, the photographs on a
 * card, the link between a bottle and the medicines given from it — asks first, because an undo that
 * returned a document row pointing at a file that no longer exists would be a lie.
 *
 * [body] is where the dialog earns its interruption: it says **what else goes**, in the same
 * sentence as what is being deleted. "Delete this?" teaches somebody to tap Delete without reading;
 * "the scan will be deleted too, and the record of the dose it proves will not" does not.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
