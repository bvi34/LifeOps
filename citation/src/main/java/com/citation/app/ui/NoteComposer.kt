package com.citation.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Writing a note against the passage you just selected.
 */

/**
 * The name a content provider gives a picked document, or null.
 *
 * Worth a query rather than reading the URI: `content://` paths carry a provider's internal document
 * id, so the file name is only available by asking, and it is the difference between a font the
 * reader can recognise in a list and one labelled by a digest of its bytes.
 */
internal fun documentName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()?.takeIf { it.isNotBlank() }

@Composable
internal fun NoteComposer(
    quote: String,
    body: String,
    onQuote: (String) -> Unit,
    onBody: (String) -> Unit,
    onSave: () -> Unit,
    onSaveSynthesis: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = quote,
            onValueChange = onQuote,
            label = { Text("Passage to cite (select text to fill, or type it)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body,
            onValueChange = onBody,
            label = { Text("Your note") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            // Synthesis: your own artifact; the quote is an optional citation, so body is enough.
            TextButton(
                onClick = onSaveSynthesis,
                enabled = body.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save as synthesis") }
            // Passage-anchored: hangs off exactly one highlight, so the quote is required.
            Button(
                onClick = onSave,
                enabled = quote.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Save passage note") }
        }
    }
}
