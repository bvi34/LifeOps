package com.citation.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.citation.core.reader.BookSearch
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Lookup

/**
 * Searching the book you are reading.
 *
 * Presented as a bar that takes over the top of the reader rather than a separate screen, because
 * search here is a *reading* action — you are looking for a passage in order to get back to it, and
 * the page you came from should still be underneath. Landing on a hit leaves the results up, so
 * finding the next occurrence is one tap rather than a re-search.
 */
@Composable
fun SearchBar(
    query: String,
    hits: List<BookSearch.Hit>,
    onQuery: (String) -> Unit,
    onHit: (BookSearch.Hit) -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text("Find in this book") },
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close search")
                }
            }

            val tooShort = query.trim().length in 1 until BookSearch.MIN_QUERY
            Text(
                text = when {
                    query.isBlank() -> "Type to search the whole book."
                    tooShort -> "Keep typing…"
                    hits.isEmpty() -> "No matches."
                    else -> "${hits.size} ${if (hits.size == 1) "match" else "matches"} " +
                        "in ${BookSearch.chaptersWithHits(hits)} " +
                        if (BookSearch.chaptersWithHits(hits) == 1) "chapter" else "chapters"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (hits.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    itemsIndexed(hits, key = { i, hit -> "$i-${hit.chapterOrdinal}-${hit.offset}" }) { _, hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onHit(hit) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                hit.chapterTitle.ifBlank { "Chapter ${hit.chapterOrdinal + 1}" },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                emboldened(hit.snippet, hit.snippetMatch),
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

/** The matched words in a snippet, set apart so a result reads as an answer rather than a line. */
private fun emboldened(snippet: String, match: IntRange): AnnotatedString = buildAnnotatedString {
    append(snippet)
    val start = match.first.coerceIn(0, snippet.length)
    val end = (match.last + 1).coerceIn(start, snippet.length)
    if (end > start) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
}

/**
 * Where the reader has been and where it can go: the book's contents, and the places you saved.
 *
 * They share a sheet because they answer the same question — "take me somewhere in this book" — and
 * splitting them across two controls made the reader's top bar a row of near-identical icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    onGo: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onLabel: (Bookmark, String) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Bookmarks",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet. The ribbon in the top bar saves the page you are on.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(bookmarks, key = { _, b -> b.key.toString() }) { _, bookmark ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onGo(bookmark) }
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    bookmark.chapterTitle.ifBlank { "Chapter ${bookmark.chapterOrdinal + 1}" },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    bookmark.display,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Serif,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { renaming = bookmark }) { Text("Label") }
                            IconButton(onClick = { onDelete(bookmark) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete bookmark",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Divider()
                    }
                }
            }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }

    renaming?.let { bookmark ->
        LabelBookmarkDialog(
            bookmark = bookmark,
            onConfirm = { onLabel(bookmark, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }
}

/** Give a bookmark a name, for when the frozen line is not what you were marking. */
@Composable
fun LabelBookmarkDialog(bookmark: Bookmark, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(bookmark.label.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label this bookmark") },
        text = {
            Column {
                Text(
                    bookmark.snippet,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("Name (optional)") },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Looking a word up.
 *
 * The platform's own dictionary action goes first — it respects whatever dictionary the reader has
 * installed and works offline if that one does. But many devices have no handler for it, and a menu
 * item that silently does nothing is worse than one that opens a page, so the web fallbacks are
 * offered in the same list rather than hidden behind a failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(query: Lookup.Query, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceDictionary = remember(query) { query.kind == Lookup.Kind.WORD && hasDefineHandler(context) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                query.term,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                fontFamily = FontFamily.Serif,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (query.truncated) {
                Text(
                    "Shortened to something searchable.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            if (deviceDictionary) {
                LookupRow("Dictionary on this device") {
                    if (!launchDefine(context, query.term)) {
                        Lookup.webUrl(query, Lookup.Target.DICTIONARY)?.let { openUrl(context, it) }
                    }
                    onDismiss()
                }
            }
            Lookup.targetsFor(query).forEach { target ->
                LookupRow(label(target)) {
                    Lookup.webUrl(query, target)?.let { openUrl(context, it) }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun LookupRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        fontSize = 16.sp
    )
}

private fun label(target: Lookup.Target): String = when (target) {
    Lookup.Target.DICTIONARY -> "Wiktionary"
    Lookup.Target.ENCYCLOPEDIA -> "Wikipedia"
    Lookup.Target.SEARCH -> "Search the web"
}

/** Whether anything on this device answers the platform's define action. */
private fun hasDefineHandler(context: Context): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_DEFINE).putExtra(Intent.EXTRA_TEXT, "word")
    intent.resolveActivity(context.packageManager) != null
}.getOrDefault(false)

private fun launchDefine(context: Context, term: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_DEFINE)
            .putExtra(Intent.EXTRA_TEXT, term)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrElse { false }

/**
 * A note, over the sentence that cited it.
 *
 * A footnote is read *in* a sentence, not instead of it: the whole reason the producer marked it as
 * a note reference rather than a link is that following it away and coming back costs more than the
 * note is usually worth. So it arrives as a sheet over the page, and the page underneath does not
 * move — which also means dismissing it needs no navigation at all.
 *
 * [cut] says the note is longer than a sheet should be, and the way to the rest is offered rather
 * than the sheet growing into a chapter. Going there is a real jump, and the reader offers the way
 * back from it like any other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootnoteSheet(text: String, cut: Boolean, onGoToNote: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                "Note",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text,
                // Serif and a reading size: this is the book's own prose, not a system message.
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            )
            if (cut) {
                TextButton(onClick = onGoToNote, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Read the whole note")
                }
            }
        }
    }
}

/**
 * Confirm leaving the book for an address the text points at.
 *
 * Asked rather than followed, because a link in a book is not a link on a page: tapping it hands
 * the reader to a browser, and a reader who meant to turn the page has lost their book to an
 * advert for it. The address is shown, since where it goes is the only thing that makes the answer
 * obvious.
 */
@Composable
fun LeaveBookDialog(url: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave the book?") },
        text = {
            Text(
                url,
                fontSize = 14.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        },
        confirmButton = {
            TextButton(onClick = { openUrl(context, url); onDismiss() }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay") } }
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { if (it !is ActivityNotFoundException) throw it }
}
