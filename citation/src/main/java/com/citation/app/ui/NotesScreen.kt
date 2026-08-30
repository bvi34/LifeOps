package com.citation.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.CitationRepository
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.note.NoteType
import com.citation.core.note.Tags
import java.text.DateFormat
import java.util.Date

/**
 * The Notes list: every captured note, searchable and tag-filterable, each showing its
 * **degradation state** and — when it can — a jump back to live context. A search box narrows by
 * body/snapshot/title/tags; a facet row of tags narrows by filing. The frozen snapshot is always
 * shown, so even an orphaned or source-unavailable note reads fully; the badge just tells you
 * whether the jump is live, shaky, or gone. This is where "a note outlives its source" — and now
 * "notes come back out again" — is visible to the reader. Rendered content-only so the Personal tab
 * can host it beneath its reading stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesList(vm: ReaderViewModel, modifier: Modifier = Modifier) {
    val notes by vm.filteredNotes.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tagCounts by vm.tagCounts.collectAsStateWithLifecycle()
    val activeTag by vm.activeTag.collectAsStateWithLifecycle()
    // Library books, offered as link targets for an unbound capture (bind its source by hand).
    val books by vm.books.collectAsStateWithLifecycle()
    // The note currently open in the detail/edit sheet (tap a row to open).
    var editing by remember { mutableStateOf<Note?>(null) }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search notes") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (tagCounts.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(tagCounts, key = { it.tag }) { tc ->
                    FilterChip(
                        selected = activeTag == tc.tag,
                        onClick = { vm.toggleTag(tc.tag) },
                        label = { Text("#${tc.tag} · ${tc.count}") }
                    )
                }
            }
        }

        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    emptyMessage(query, activeTag),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(notes, key = { it.key.toString() }) { note ->
                    NoteRow(note, vm, onClick = { editing = note })
                }
            }
        }
    }

    editing?.let { note ->
        NoteDetailDialog(
            note = note,
            onSave = { body -> vm.editNote(note.key.toString(), body); editing = null },
            onSaveTags = { raw -> vm.setNoteTags(note.key.toString(), raw) },
            onSaveHighlight = { color -> vm.setNoteHighlight(note.key.toString(), color) },
            onJump = { vm.jumpToNote(note); editing = null },
            onDelete = { vm.deleteNote(note.key.toString()); editing = null },
            onDismiss = { editing = null },
            linkTargets = books,
            onLink = { bookKey -> vm.linkNoteToBook(note.key.toString(), bookKey); editing = null }
        )
    }
}

/** What the empty state says depends on whether a filter is hiding notes or there simply are none. */
private fun emptyMessage(query: String, activeTag: String?): String =
    if (query.isNotBlank() || activeTag != null) "No notes match."
    else "No notes yet. Highlight a passage while reading, or use “Save to Citation” from any app."

@Composable
private fun NoteRow(note: Note, vm: ReaderViewModel, onClick: () -> Unit) {
    // Resolve the note's state off the UI thread; default to the most optimistic while loading.
    val state by produceState(initialValue = NoteResolver.State.RESOLVED, note) {
        value = vm.overallState(note)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (note.type == NoteType.PASSAGE_ANCHORED) "Passage note" else "Synthesis",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
            StateBadge(state)
        }
        // The frozen snapshot — always readable, source or no source.
        note.references.firstOrNull()?.let { ref ->
            Text(
                text = "“${ref.quotedSnapshot}”",
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (note.body.isNotBlank()) {
            Text(
                text = note.body,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        } else {
            Text(
                text = "Tap to add a note",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (note.tags.isNotEmpty()) {
            Text(
                text = Tags.format(note.tags),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            text = "${note.source.title} · ${formatWhen(note.createdAt)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/**
 * Open one note to read it in full and **add your own words**. A captured quote arrives with an empty
 * body; this is where you annotate it — and, when [onSaveTags] is supplied, file it with tags. When
 * the note is bound to a source, a "Jump to source" button appears; when it's an unbound capture and
 * [onLink] + [linkTargets] are supplied, a "Link to a book" button lets you bind its source by hand
 * (the manual counterpart to automatic promotion). Otherwise you just read the frozen snapshot and
 * write your note.
 *
 * @param linkTargets library books offered as link targets for an unbound capture.
 * @param onLink called with a chosen book's key to bind this capture's source to it.
 */
@Composable
fun NoteDetailDialog(
    note: Note,
    onSave: (String) -> Unit,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSaveTags: ((String) -> Unit)? = null,
    onSaveHighlight: ((HighlightColor) -> Unit)? = null,
    linkTargets: List<CitationRepository.BookSummary> = emptyList(),
    onLink: ((String) -> Unit)? = null
) {
    var body by remember(note.key) { mutableStateOf(note.body) }
    var tagsText by remember(note.key) { mutableStateOf(Tags.format(note.tags)) }
    var color by remember(note.key) { mutableStateOf(note.highlightColor) }
    var picking by remember(note.key) { mutableStateOf(false) }
    val canJump = note.source.bookKey != null
    // An unbound capture can be linked to a book you already hold — bind its source by hand.
    val canLink = note.source.bookKey == null && onLink != null && linkTargets.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(note.source.title) },
        text = {
            Column {
                note.references.firstOrNull()?.let { ref ->
                    Text(
                        "“${ref.quotedSnapshot}”",
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "Captured ${formatWhen(note.createdAt)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Your note") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (onSaveTags != null) {
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("Tags") },
                        placeholder = { Text("#stoicism #deep-work") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
                // The other half of filing: tags are what you search, the colour is what you see
                // while turning pages. Offered here rather than at capture so marking a passage
                // stays one gesture — you file it in the colour you are working in, and change the
                // odd one afterwards.
                if (onSaveHighlight != null && note.references.isNotEmpty()) {
                    HighlightColorRow(selected = color, onPick = { color = it })
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    if (canJump) {
                        TextButton(onClick = onJump) { Text("Jump to source") }
                    }
                    if (canLink) {
                        TextButton(onClick = { picking = true }) { Text("Link to a book") }
                    }
                    onDelete?.let {
                        TextButton(onClick = it) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSaveTags?.invoke(tagsText)
                if (color != note.highlightColor) onSaveHighlight?.invoke(color)
                onSave(body)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    if (picking && onLink != null) {
        LinkBookPicker(
            books = linkTargets,
            onPick = { bookKey -> picking = false; onLink(bookKey) },
            onDismiss = { picking = false }
        )
    }
}

/**
 * The five highlight colours, as swatches.
 *
 * Named colours rather than a picker, and that is the point rather than a shortcut: a highlight
 * colour is only worth anything if it *means* something — yellow for what the book says, blue for
 * what you argue with — and a meaning has to hold across a year of reading. Five you can tell apart
 * and remember beats a million you cannot.
 *
 * Each swatch is drawn at full strength here. On the page it is mixed into whatever colour the
 * reader set their pages to, which is why a yellow chip and a yellow mark do not match exactly.
 */
@Composable
private fun HighlightColorRow(selected: HighlightColor, onPick: (HighlightColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HighlightColor.entries.forEach { option ->
            val chosen = option == selected
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(option.tint))
                    .border(
                        width = if (chosen) 3.dp else 1.dp,
                        color = if (chosen) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .selectable(selected = chosen, role = Role.RadioButton) { onPick(option) }
                    .semantics { contentDescription = option.label }
            )
        }
    }
}

/**
 * Pick a library book to bind an unbound capture's source to. A plain, searchless list — one person's
 * library is small — of every book you hold; tapping one links the capture (and its cluster siblings).
 */
@Composable
private fun LinkBookPicker(
    books: List<CitationRepository.BookSummary>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to a book") },
        text = {
            if (books.isEmpty()) {
                Text(
                    "Your library is empty — add a book first, then link this capture to it.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(books, key = { it.key }) { book ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(book.key) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                book.title,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            book.author?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
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

/** A friendly absolute date+time for when a note was captured. */
private fun formatWhen(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

@Composable
private fun StateBadge(state: NoteResolver.State) {
    val (label, color) = when (state) {
        NoteResolver.State.RESOLVED -> "Linked" to Color(0xFF2E7D32)
        NoteResolver.State.FUZZY -> "Shifted" to Color(0xFFF9A825)
        NoteResolver.State.ORPHANED -> "Orphaned" to Color(0xFFB0653B)
        NoteResolver.State.SOURCE_UNAVAILABLE -> "Source gone" to Color(0xFF6D6D6D)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
