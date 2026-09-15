package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.addKindleBook
import com.citation.app.data.addOreillyBook
import com.citation.app.data.beginReaderContext
import com.citation.app.data.captureExternalNote
import com.citation.app.data.endReaderContext
import com.citation.app.data.kindleLibrary
import com.citation.app.data.kindleSession
import com.citation.app.data.saveExternalPosition
import com.citation.core.note.Note
import kotlinx.coroutines.launch

/**
 * Kindle read-in-place on read.amazon.com.
 */

/**
 * Register a Kindle book by ASIN and open it read-in-place in the Cloud Reader. The counterpart to
 * [addOreillyBook] — add it, and you're reading (deduped by ASIN, so re-adding reuses its notes).
 */
internal fun ReaderViewModel.addKindleBook(asin: String, title: String) {
    viewModelScope.launch {
        val key = repository.addKindleBook(asin, title)
        repository.markOpened(key)
        _kindleSession.value = repository.kindleSession(key)
        repository.beginReaderContext(key)
        startReadingSession(key)
    }
}

/** Open your Kindle library on read.amazon.com to browse and pick a book (learns its ASIN + title). */
internal fun ReaderViewModel.browseKindle() {
    _kindleLibrary.value = repository.kindleLibrary()
}

/** Leave the Kindle library without opening anything. */
internal fun ReaderViewModel.closeKindleLibrary() { _kindleLibrary.value = null }

/**
 * Open a book tapped in the Kindle library: register it read-in-place with the ASIN + learned title
 * (deduped by ASIN, so re-picking the same book reuses its notes) and hand off to the reader. The
 * Kindle counterpart to [openOreillyFromCatalog] — browse the shelf, tap, and you're reading.
 */
internal fun ReaderViewModel.openKindleFromLibrary(asin: String, title: String) {
    viewModelScope.launch {
        val key = repository.addKindleBook(asin, title)
        repository.markOpened(key)
        _kindleLibrary.value = null
        _kindleSession.value = repository.kindleSession(key)
        repository.beginReaderContext(key)
        startReadingSession(key)
    }
}

internal fun ReaderViewModel.closeKindle() {
    _kindleSession.value?.bookKey?.let { repository.endReaderContext(it) }
    endReadingSession()
    _kindleSession.value = null
}

/** Persist the Kindle reader's last position label so the library shows where you were. */
internal fun ReaderViewModel.saveKindlePosition(location: String) {
    onReadingProgress()
    val session = _kindleSession.value ?: return
    viewModelScope.launch { repository.saveExternalPosition(session.bookKey, location) }
}

/**
 * Capture a note on the open Kindle book. `read.amazon.com` blocks copying the passage, so the
 * quote is normally unavailable: the reader's position label ([location], e.g. "Location 156 of
 * 3866") stands in as the citation. A real [quote] is still honoured when present — in case text
 * selection ever works — and only falls back to the location when left blank. The book is the
 * source; the annotation is your own.
 */
internal fun ReaderViewModel.captureKindleNote(location: String, quote: String, body: String) {
    val session = _kindleSession.value ?: return
    viewModelScope.launch {
        val cited = quote.ifBlank { location }
        val note = repository.captureExternalNote(session.bookKey, location, cited, body)
        _status.value = "Note ${note.key} captured on Kindle book."
    }
}
