package com.citation.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.coverFile
import com.citation.app.data.createCollection
import com.citation.app.data.refreshFromFile
import com.citation.app.data.setFavorite
import com.citation.app.data.setReadingState
import com.citation.core.library.BookCollection
import com.citation.core.library.LibraryEntry
import com.citation.core.library.LibrarySort
import com.citation.core.model.SourceType
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.ReadingState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The library.
 *
 * It used to be a flat scrolling column of titles with two state words under each — workable for a
 * dozen books, useless for the hundreds that a catalog connection makes normal within a week. This
 * is the shelf that replaces it: covers, search, sort, shelves you make yourself, and enough
 * metadata per book to recognise it without opening it.
 *
 * All the arranging is pure `:core` (`LibraryQuery`), so what happens here is presentation only.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: ReaderViewModel) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val everything by vm.library.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val facets by vm.libraryFacets.collectAsStateWithLifecycle()
    val filter by vm.libraryFilter.collectAsStateWithLifecycle()
    val sort by vm.librarySort.collectAsStateWithLifecycle()
    val grid by vm.libraryGrid.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<LibraryEntry?>(null) }
    var newShelf by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (filter.isEmpty) "Library" else "${shelf.size} of ${everything.size}") },
                actions = {
                    IconButton(onClick = { vm.toggleFavoritesFilter() }) {
                        Icon(
                            if (filter.favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Starred only"
                        )
                    }
                    IconButton(onClick = { vm.setLibraryGrid(!grid) }) {
                        Icon(
                            if (grid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = if (grid) "Show as a list" else "Show as a grid"
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.label,
                                            fontWeight = if (option == sort) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { vm.setLibrarySort(option); sortMenu = false }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = vm::setLibraryQuery,
                singleLine = true,
                label = { Text("Search title, author, series, subject") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ShelfChips(
                collections = collections,
                activeCollection = filter.collectionId,
                subjects = facets.map { it.name },
                activeSubject = filter.subject,
                onCollection = vm::toggleCollectionFilter,
                onSubject = vm::toggleSubjectFilter,
                onNewShelf = { newShelf = true }
            )

            when {
                everything.isEmpty() -> Empty("Your library is empty. Add something from the New tab.")
                shelf.isEmpty() -> Empty("Nothing matches. Clear the filters to see everything.") {
                    TextButton(onClick = { vm.clearLibraryFilter() }) { Text("Clear filters") }
                }
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(shelf, key = { it.key }) { entry ->
                        GridBook(
                            entry = entry,
                            cover = { vm.coverFile(entry.key) },
                            onOpen = { vm.open(entry.key) },
                            onDetail = { detail = entry }
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shelf, key = { it.key }) { entry ->
                        ListBook(
                            entry = entry,
                            cover = { vm.coverFile(entry.key) },
                            onOpen = { vm.open(entry.key) },
                            onDetail = { detail = entry }
                        )
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        BookDetailSheet(
            entry = entry,
            collections = collections,
            cover = { vm.coverFile(entry.key) },
            onOpen = { vm.open(entry.key); detail = null },
            onFavorite = { vm.setFavorite(entry.key, it) },
            onReadingState = { vm.setReadingState(entry.key, it) },
            onCollection = { id, member -> vm.setCollectionMembership(id, entry.key, member) },
            onRefresh = { vm.refreshFromFile(entry.key); detail = null },
            onDelete = { vm.deleteBook(entry.key, entry.title); detail = null },
            onDismiss = { detail = null }
        )
    }

    if (newShelf) {
        NameShelfDialog(
            onConfirm = { vm.createCollection(it); newShelf = false },
            onDismiss = { newShelf = false }
        )
    }
}

@Composable
private fun Empty(message: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(24.dp)
            )
            action?.invoke()
        }
    }
}

/**
 * The filter row: your own shelves first, then the subjects the books brought with them.
 *
 * Subjects are shown from the *unfiltered* library so the row does not collapse as you narrow —
 * a facet row that disappears while you use it is worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfChips(
    collections: List<BookCollection>,
    activeCollection: String?,
    subjects: List<String>,
    activeSubject: String?,
    onCollection: (String) -> Unit,
    onSubject: (String) -> Unit,
    onNewShelf: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        collections.forEach { shelf ->
            FilterChip(
                selected = activeCollection == shelf.id,
                onClick = { onCollection(shelf.id) },
                label = { Text(shelf.name, maxLines = 1) }
            )
        }
        AssistChip(onClick = onNewShelf, label = { Text("+ Shelf") })
        subjects.forEach { subject ->
            FilterChip(
                selected = activeSubject == subject,
                onClick = { onSubject(subject) },
                label = { Text(subject, maxLines = 1) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBook(
    entry: LibraryEntry,
    cover: () -> File?,
    onOpen: () -> Unit,
    onDetail: () -> Unit
) {
    Column(
        Modifier.combinedClickable(onClick = onOpen, onLongClick = onDetail)
    ) {
        Cover(entry = entry, cover = cover, modifier = Modifier.fillMaxWidth().aspectRatio(0.66f))
        Text(
            entry.title,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        entry.author?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ProgressLine(entry)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBook(
    entry: LibraryEntry,
    cover: () -> File?,
    onOpen: () -> Unit,
    onDetail: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDetail)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Cover(entry = entry, cover = cover, modifier = Modifier.width(44.dp).height(66.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(entry.title, fontSize = 16.sp, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.author?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            entry.seriesLabel?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            ProgressLine(entry)
        }
        if (entry.isFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Starred",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Progress, stated in words rather than only as a bar — "Finished", "42%", or what a book is
 * waiting on. A bar alone at 3% is indistinguishable from a bar at 0%.
 */
@Composable
private fun ProgressLine(entry: LibraryEntry) {
    val label = when {
        entry.acquisitionState != AcquisitionState.ACQUIRED -> entry.acquisitionState.name.lowercase()
        entry.readingState == ReadingState.DONE -> "Finished"
        entry.progress <= 0f -> "Not started"
        else -> "${(entry.progress * 100).toInt()}%"
    }
    Column(Modifier.padding(top = 4.dp)) {
        if (entry.progress > 0f && entry.readingState != ReadingState.DONE) {
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * A book's cover, or a woven fallback built from its own title.
 *
 * The fallback matters more than the covers do: most libraries are a mix, and a grid where half the
 * cells are empty grey rectangles is harder to scan than one with no covers at all. Deriving the
 * tint from the title keeps a book looking like itself between sessions.
 */
@Composable
private fun Cover(entry: LibraryEntry, cover: () -> File?, modifier: Modifier = Modifier) {
    val bitmap = rememberCover(entry.key, cover)
    val shape = RoundedCornerShape(4.dp)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(
            modifier
                .clip(shape)
                .background(fallbackTint(entry.title)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                entry.title.take(2).uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }
}

/** Decode a cover off the main thread, downsampled — a shelf must not decode full-size art. */
@Composable
private fun rememberCover(key: String, cover: () -> File?): ImageBitmap? {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.IO) {
            val file = cover() ?: return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= COVER_TARGET_PX) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

private const val COVER_TARGET_PX = 320

private fun fallbackTint(title: String): Color {
    val hues = listOf(0xFF4E6E81, 0xFF6B4E81, 0xFF81614E, 0xFF4E815F, 0xFF7A4E5E, 0xFF4E5A81)
    return Color(hues[(title.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % hues.size])
}

/**
 * One book, in full: what it is, where you are in it, and the few things worth doing to it without
 * opening it — star it, mark it finished, put it on a shelf, remove it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailSheet(
    entry: LibraryEntry,
    collections: List<BookCollection>,
    cover: () -> File?,
    onOpen: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onReadingState: (ReadingState) -> Unit,
    onCollection: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row {
                Cover(entry = entry, cover = cover, modifier = Modifier.width(88.dp).height(132.dp))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(entry.title, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    entry.author?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary) }
                    entry.seriesLabel?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(
                        entry.sourceType.name.lowercase().replace('_', ' '),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    ProgressLine(entry)
                }
            }

            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text(if (entry.isStarted) "Continue" else "Read") }
                OutlinedButton(onClick = { onFavorite(!entry.isFavorite) }) {
                    Text(if (entry.isFavorite) "Unstar" else "Star")
                }
                OutlinedButton(
                    onClick = {
                        onReadingState(
                            if (entry.readingState == ReadingState.DONE) ReadingState.READING else ReadingState.DONE
                        )
                    }
                ) {
                    Text(if (entry.readingState == ReadingState.DONE) "Reading" else "Finished")
                }
            }

            if (entry.subjects.isNotEmpty()) {
                Text(
                    entry.subjects.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (collections.isNotEmpty()) {
                Text("Shelves", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                collections.forEach { shelf ->
                    val member = shelf.id in entry.collectionIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = member, onCheckedChange = { onCollection(shelf.id, it) })
                        Text(shelf.name, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            // Only owned snapshots have a file to re-read; a serial or a read-in-place licence
            // has nothing on disk to learn more from.
            if (entry.sourceType == SourceType.EPUB || entry.sourceType == SourceType.AO3) {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Refresh details from the file")
                }
            }

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Remove from library", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove “${entry.title}”?") },
            text = {
                Text(
                    "The book leaves your library. Notes and highlights you took from it stay — they " +
                        "keep their frozen quotes, so they remain readable without it."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NameShelfDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New shelf") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
