package com.citation.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.core.model.Book
import com.citation.core.model.TocEntry

/**
 * The table of contents sheet, and one row of it.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TocSheet(
    book: Book,
    current: Int,
    bookmarkCount: Int,
    onOpenBookmarks: () -> Unit,
    onSelect: (Int) -> Unit,
    onSelectEntry: (TocEntry) -> Unit,
    onDismiss: () -> Unit
) {
    // The publisher's own contents when the book has one, otherwise the spine — which is all the
    // reader ever used to show. For anything longer than a novel the difference is the difference
    // between "Part II › Chapter 7 › Consistent Hashing" and a hundred numbered files.
    val entries = remember(book) { book.toc.flatten() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (entries.isEmpty()) "Chapters" else "Contents",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                // Contents and bookmarks answer the same question — take me somewhere in this book
                // — so they share a route rather than each claiming a top-bar icon of their own.
                TextButton(onClick = onOpenBookmarks) {
                    Text(if (bookmarkCount > 0) "Bookmarks ($bookmarkCount)" else "Bookmarks")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                if (entries.isEmpty()) {
                    itemsIndexed(book.chapters) { i, ch ->
                        TocRow(
                            label = "${i + 1}.  ${ch.title.ifBlank { "Chapter ${i + 1}" }}",
                            depth = 0,
                            selected = i == current,
                            enabled = true,
                            onClick = { onSelect(i) }
                        )
                    }
                } else {
                    itemsIndexed(entries) { _, (entry, depth) ->
                        TocRow(
                            label = entry.title,
                            depth = depth,
                            selected = entry.chapterOrdinal == current,
                            // An entry whose target isn't in the spine is still shown — it is part
                            // of the book's shape — but there is nowhere to send you.
                            enabled = entry.chapterOrdinal != null,
                            onClick = { onSelectEntry(entry) }
                        )
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun TocRow(
    label: String,
    depth: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = (20 + depth * 16).dp,
                end = 20.dp,
                top = if (depth == 0) 12.dp else 8.dp,
                bottom = if (depth == 0) 12.dp else 8.dp
            ),
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            !enabled -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (selected || depth == 0) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = if (depth == 0) 16.sp else 15.sp,
        fontFamily = FontFamily.Serif
    )
}
