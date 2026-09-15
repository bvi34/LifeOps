package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.RefreshResult
import com.citation.app.data.addToCollection
import com.citation.app.data.coverFile
import com.citation.app.data.createCollection
import com.citation.app.data.deleteCollection
import com.citation.app.data.refreshFromFile
import com.citation.app.data.removeFromCollection
import com.citation.app.data.renameCollection
import com.citation.app.data.setFavorite
import com.citation.app.data.setReadingState
import com.citation.app.data.sync
import com.citation.core.library.LibraryFilter
import com.citation.core.library.LibrarySort
import kotlinx.coroutines.launch

/**
 * The library shelf: what is on it, how it is filtered, sorted and laid out.
 */

internal fun ReaderViewModel.setLibraryQuery(text: String) {
    _libraryFilter.value = _libraryFilter.value.copy(query = text)
}

internal fun ReaderViewModel.setLibrarySort(sort: LibrarySort) { _librarySort.value = sort }

internal fun ReaderViewModel.setLibraryGrid(grid: Boolean) { _libraryGrid.value = grid }

/** Tapping the active shelf clears it; tapping another switches to it. */
internal fun ReaderViewModel.toggleCollectionFilter(id: String?) {
    val current = _libraryFilter.value
    _libraryFilter.value = current.copy(collectionId = if (current.collectionId == id) null else id)
}

internal fun ReaderViewModel.toggleSubjectFilter(subject: String) {
    val current = _libraryFilter.value
    _libraryFilter.value = current.copy(subject = if (current.subject == subject) null else subject)
}

internal fun ReaderViewModel.toggleFavoritesFilter() {
    val current = _libraryFilter.value
    _libraryFilter.value = current.copy(favoritesOnly = !current.favoritesOnly)
}

internal fun ReaderViewModel.clearLibraryFilter() {
    _libraryFilter.value = LibraryFilter(query = _libraryFilter.value.query)
}

/** The cover file for a book, or null — the shelf decodes it directly, downsampled. */
internal fun ReaderViewModel.coverFile(bookKey: String): java.io.File? = repository.coverFile(bookKey)

internal fun ReaderViewModel.setFavorite(bookKey: String, favorite: Boolean) {
    viewModelScope.launch { repository.setFavorite(bookKey, favorite) }
}

internal fun ReaderViewModel.setReadingState(bookKey: String, state: com.citation.core.sync.ReadingState) {
    viewModelScope.launch { repository.setReadingState(bookKey, state) }
}

/**
 * Re-read a book from the file it was imported from, so an older import picks up structure,
 * a cover and shelf metadata the parser can now see. Only chapters whose text is unchanged are
 * touched, so notes stay anchored exactly where they were.
 */
internal fun ReaderViewModel.refreshFromFile(bookKey: String) {
    viewModelScope.launch {
        _status.value = when (val result = repository.refreshFromFile(bookKey)) {
            is RefreshResult.Refreshed ->
                if (result.unchanged == 0) "Refreshed ${result.chapters} chapters from the file."
                else "Refreshed ${result.chapters} chapters; ${result.unchanged} were left as they were."
            RefreshResult.NotRefreshable -> "This one has no stored file to re-read."
            RefreshResult.FileMissing -> "The original file isn’t in the store any more."
            RefreshResult.Unreadable -> "Couldn’t re-read the file."
        }
    }
}

internal fun ReaderViewModel.createCollection(name: String) {
    viewModelScope.launch { repository.createCollection(name) }
}

internal fun ReaderViewModel.renameCollection(id: String, name: String) {
    viewModelScope.launch { repository.renameCollection(id, name) }
}

internal fun ReaderViewModel.deleteCollection(id: String) {
    viewModelScope.launch {
        repository.deleteCollection(id)
        if (_libraryFilter.value.collectionId == id) toggleCollectionFilter(null)
    }
}

internal fun ReaderViewModel.setCollectionMembership(collectionId: String, bookKey: String, member: Boolean) {
    viewModelScope.launch {
        if (member) repository.addToCollection(collectionId, bookKey)
        else repository.removeFromCollection(collectionId, bookKey)
    }
}
