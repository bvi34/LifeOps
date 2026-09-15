package com.project.app.ui.docs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.project.app.logic.DocHeading

/**
 * The document's own contents sheet, and sharing what it holds.
 */

/**
 * Hand [markdown] to whatever the device can send text with.
 *
 * `ACTION_SEND` with plain text rather than a file: it needs no permission and no FileProvider, and
 * every note-taking app, mail client and messenger on the device accepts it. Writing a temporary
 * file to share would be a bigger promise than "get this text out of here".
 */
internal fun shareText(context: android.content.Context, title: String, markdown: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share document")) }
}

/**
 * The document's own contents, and a way to land on one.
 *
 * A long document is a scroll bar and a hope without one. Levels are shown as indentation rather
 * than as numbers, because the shape of a draft — three scenes under this chapter, none under that
 * one — is the thing you are looking at the list to see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(
    headings: List<DocHeading>,
    onDismiss: () -> Unit,
    onGo: (DocHeading) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            item(key = "contents-title") {
                Text(
                    "Contents",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(headings, key = { it.blockId }) { heading ->
                Text(
                    text = heading.text.ifBlank { "Untitled section" },
                    style = when (heading.level) {
                        1 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        2 -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.bodySmall
                    },
                    color = if (heading.level == 1) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGo(heading) }
                        .padding(start = ((heading.level - 1) * 16).dp, top = 10.dp, bottom = 10.dp)
                )
            }
        }
    }
}
