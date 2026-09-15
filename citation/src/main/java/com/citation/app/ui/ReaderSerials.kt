package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Serials: Royal Road and AO3, which are read as they are published rather than owned.
 */

/**
 * Open a Royal Road serial *through the reader* — the WebView is only for skimming/catalog, so
 * even chapter 1 comes back as internal-model text here, identical to every later chapter. The
 * serial is registered as a sovereign book, so notes on it work like any other source.
 */
internal fun ReaderViewModel.openRoyalRoad(fictionId: Long) {
    viewModelScope.launch {
        _status.value = "Fetching Royal Road catalog…"
        runCatching { repository.openRoyalRoad(fictionId) }
            .onSuccess { book ->
                openRrFictionId = fictionId
                book.key?.let { repository.markOpened(it.toString()) }
                _openBook.value = book
                _chapterOrdinal.value = 0
                _status.value = "Opened “${book.metadata.title}”."
                startReadingSession(book.key?.toString())
            }
            .onFailure { _status.value = "Couldn’t open that Royal Road story." }
    }
}

internal fun ReaderViewModel.favoriteRoyalRoad() {
    val fictionId = openRrFictionId ?: return
    viewModelScope.launch {
        repository.royalRoad.markFavorite(fictionId, true)
        _status.value = "Favourited — full backfill queued, kept indefinitely."
    }
}

/**
 * Open an Archive of Our Own work: download its official EPUB the first time (imported as an owned
 * snapshot), then open it through the reader like any owned book. The WebView is only for
 * finding the work; reading always happens on the parsed internal model.
 */
internal fun ReaderViewModel.openAo3(workId: Long) {
    viewModelScope.launch {
        _status.value = "Downloading from Archive of Our Own…"
        runCatching { repository.openAo3(workId) }
            .onSuccess { book ->
                openRrFictionId = null
                book.key?.let { repository.markOpened(it.toString()) }
                _openBook.value = book
                _chapterOrdinal.value = 0
                _status.value = "Opened “${book.metadata.title}”."
                startReadingSession(book.key?.toString())
            }
            .onFailure { _status.value = "Couldn’t download that Archive of Our Own work." }
    }
}
