package com.citation.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.citation.app.data.acquire
import com.citation.app.data.addCatalog
import com.citation.app.data.deleteCatalog
import com.citation.app.data.openCatalog
import com.citation.app.data.searchCatalog
import com.citation.app.data.setCatalogCredentials
import com.citation.core.opds.CatalogSource
import com.citation.core.opds.OpdsEntry
import com.citation.core.opds.OpdsFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The catalogs you can browse, and adding your own.
 *
 * This is the screen that changes what Citation is: with one address it reaches a self-hosted
 * Calibre content server or Calibre-Web instance, Standard Ebooks, Project Gutenberg, Feedbooks,
 * Kavita or Komga — anything speaking OPDS. The presets are seeds so the screen is useful before
 * anything is typed; the point of it is the server you already run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogsScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    val catalogs by vm.catalogs.collectAsStateWithLifecycle()
    val page by vm.catalogPage.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CatalogSource?>(null) }

    LaunchedEffect(Unit) { vm.ensureCatalogs() }

    // Browsing preempts the list, so the whole flow lives under one entry point.
    if (page != null) {
        CatalogBrowseScreen(vm, onClose = { vm.closeCatalog() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogs") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { TextButton(onClick = { adding = true }) { Text("Add") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    "Browse an OPDS catalog — your own Calibre server, or a public library of free " +
                        "books. Anything you download lands in your library with its details attached.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            items(catalogs, key = { it.id }) { source ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.openCatalog(source) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(
                                source.rootUrl,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (source.requiresAuth) {
                                Text(
                                    "Signed in as ${source.username}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        TextButton(onClick = { editing = source }) { Text("Edit") }
                    }
                }
                Divider()
            }
            if (catalogs.isEmpty()) {
                item {
                    Text(
                        "No catalogs yet. Add one with its address — for a Calibre server that is " +
                            "usually the machine and port, like nas.local:8080.",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }

    if (adding) {
        CatalogDialog(
            title = "Add a catalog",
            onConfirm = { name, url, user, password ->
                vm.addCatalog(name, url, user, password)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    editing?.let { source ->
        CatalogDialog(
            title = source.name,
            initialName = source.name,
            initialUrl = source.rootUrl,
            initialUser = source.username.orEmpty(),
            urlEditable = false,
            onConfirm = { _, _, user, password ->
                vm.setCatalogCredentials(source.id, user, password)
                editing = null
            },
            onDelete = { vm.deleteCatalog(source.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/**
 * Browsing one catalog: folders, books, search, facets and paging.
 *
 * Every entry here is data rather than a web page, which is the entire reason this is not a
 * WebView: a book can be downloaded straight into the library with its series, subjects and blurb,
 * and a folder can be walked into and back out of with the system Back gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBrowseScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    val page by vm.catalogPage.collectAsStateWithLifecycle()
    val loading by vm.catalogLoading.collectAsStateWithLifecycle()
    val error by vm.catalogError.collectAsStateWithLifecycle()
    val acquiring by vm.acquiring.collectAsStateWithLifecycle()

    val current = page ?: return
    val feed = current.feed
    var searching by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<OpdsEntry?>(null) }

    // Back walks up the catalog first, and only leaves once there is nowhere left to go up to.
    BackHandler { if (!vm.catalogBack()) onClose() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { if (!vm.catalogBack()) onClose() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (feed.search != null) {
                            IconButton(onClick = { searching = !searching }) {
                                Icon(
                                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = "Search this catalog"
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = terms,
                        onValueChange = { terms = it },
                        singleLine = true,
                        label = { Text("Search") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { vm.searchCatalog(terms); searching = false },
                        enabled = terms.isNotBlank()
                    ) { Text("Go") }
                }
            }

            error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(message, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.retryCatalog() }) { Text("Retry") }
                    }
                }
            }

            Facets(feed = feed, onFacet = vm::followCatalogLink)

            LazyColumn(Modifier.fillMaxSize()) {
                // Keyed by position as well as identity: a feed may repeat an id, or state none at
                // all, and a duplicate key crashes a lazy list rather than merely looking wrong.
                itemsIndexed(feed.navigation, key = { i, e -> "nav-$i-${e.id ?: e.title}" }) { _, entry ->
                    FolderRow(entry) { entry.navigationHref?.let(vm::followCatalogLink) }
                    Divider()
                }
                itemsIndexed(feed.publications, key = { i, e -> "pub-$i-${e.id ?: e.title}" }) { _, entry ->
                    PublicationRow(
                        entry = entry,
                        busy = (entry.id ?: entry.title) in acquiring,
                        thumbnail = { url -> vm.catalogThumbnail(url) },
                        onOpen = { detail = entry },
                        onAcquire = { vm.acquire(entry) }
                    )
                    Divider()
                }
                feed.next?.let { next ->
                    item {
                        OutlinedButton(
                            onClick = { vm.followCatalogLink(next) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) { Text("Load more") }
                    }
                }
                if (feed.isEmpty && !loading) {
                    item {
                        Text(
                            "Nothing here.",
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        EntrySheet(
            entry = entry,
            busy = (entry.id ?: entry.title) in acquiring,
            thumbnail = { url -> vm.catalogThumbnail(url) },
            onAcquire = { vm.acquire(entry) },
            onDismiss = { detail = null }
        )
    }
}

/** Facet groups the catalog offers — "Sort by", "Language" — as one chip row per heading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Facets(feed: OpdsFeed, onFacet: (String) -> Unit) {
    val groups = remember(feed) { feed.facets }
    if (groups.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        groups.forEach { group ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(group.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                group.facets.forEach { facet ->
                    FilterChip(
                        selected = facet.activeFacet,
                        onClick = { onFacet(facet.href) },
                        label = {
                            Text(
                                facet.title ?: facet.href.substringAfterLast('/'),
                                maxLines = 1
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(entry: OpdsEntry, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        entry.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PublicationRow(
    entry: OpdsEntry,
    busy: Boolean,
    thumbnail: suspend (String) -> ByteArray?,
    onOpen: () -> Unit,
    onAcquire: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        CatalogCover(entry = entry, thumbnail = thumbnail, modifier = Modifier.width(44.dp).height(66.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(entry.title, fontSize = 16.sp, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
            entry.author?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            entry.seriesLabel?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
        }
        Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                entry.preferredDownload != null ->
                    TextButton(onClick = onAcquire) { Text(entry.preferredDownload!!.formatLabel) }
                // A book that can only be borrowed or bought is shown honestly rather than with a
                // button that would fail — Citation does not drive a lending flow.
                entry.borrowLinks.isNotEmpty() ->
                    Text("Borrow", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                entry.buyLinks.isNotEmpty() ->
                    Text("Buy", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    entry: OpdsEntry,
    busy: Boolean,
    thumbnail: suspend (String) -> ByteArray?,
    onAcquire: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row {
                CatalogCover(entry = entry, thumbnail = thumbnail, modifier = Modifier.width(88.dp).height(132.dp))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(entry.title, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    entry.author?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary) }
                    entry.seriesLabel?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                    }
                    listOfNotNull(entry.publisher, entry.published).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it.joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            entry.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
            }

            if (entry.categories.isNotEmpty()) {
                Text(
                    entry.categories.joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Downloading…", modifier = Modifier.padding(start = 12.dp))
                }
                entry.downloads.isNotEmpty() -> Column {
                    Button(onClick = onAcquire, modifier = Modifier.fillMaxWidth()) {
                        Text("Download ${entry.preferredDownload?.formatLabel.orEmpty()}".trim())
                    }
                    if (entry.downloads.size > 1) {
                        Text(
                            "Also offered as " + entry.downloads.drop(1).joinToString(", ") { it.formatLabel },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                else -> Text(
                    "This catalog doesn’t offer a direct download for this book.",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

/** A catalog cover, fetched lazily; falls back to the same woven tile the library uses. */
@Composable
private fun CatalogCover(
    entry: OpdsEntry,
    thumbnail: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier
) {
    val url = entry.thumbnail
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = url?.let { address ->
            val bytes = thumbnail(address) ?: return@let null
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val shape = RoundedCornerShape(4.dp)
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape)
        )
    } else {
        Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                entry.title.take(2).uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun CatalogDialog(
    title: String,
    initialName: String = "",
    initialUrl: String = "",
    initialUser: String = "",
    urlEditable: Boolean = true,
    onConfirm: (String, String, String, String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (urlEditable) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text("Address") },
                        placeholder = { Text("nas.local:8080") }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name (optional)") },
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Only needed if the server asks you to sign in.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier.padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, user, password) },
                enabled = !urlEditable || url.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
