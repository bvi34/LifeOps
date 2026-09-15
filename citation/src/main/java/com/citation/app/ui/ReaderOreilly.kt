package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.addOreillyBook
import com.citation.app.data.beginReaderContext
import com.citation.app.data.captureExternalNote
import com.citation.app.data.capturePdfNote
import com.citation.app.data.clearOreillyCredentials
import com.citation.app.data.endReaderContext
import com.citation.app.data.oreillyAccessConfig
import com.citation.app.data.oreillyCatalog
import com.citation.app.data.oreillySession
import com.citation.app.data.saveExternalPosition
import com.citation.app.data.setOreillyCredentials
import com.citation.app.data.setOreillyProxyHost
import com.citation.core.note.Note
import kotlinx.coroutines.launch

/**
 * O'Reilly read-in-place: the library card that reaches it, and the session it opens.
 */

/**
 * Save the library proxy host and, when both are given, the card + PIN (encrypted). A blank card
 * or PIN leaves any stored credential untouched, so re-saving just the host is safe.
 */
internal fun ReaderViewModel.saveOreillyAccess(proxyHost: String, card: String, pin: String) {
    viewModelScope.launch {
        repository.setOreillyProxyHost(proxyHost)
        if (card.isNotBlank() && pin.isNotBlank()) repository.setOreillyCredentials(card, pin)
        _oreillyConfig.value = repository.oreillyAccessConfig()
        _status.value = "O'Reilly library access saved."
    }
}

/** Forget the stored library card + PIN (keeps the proxy host). */
internal fun ReaderViewModel.clearOreillyCredentials() {
    viewModelScope.launch {
        repository.clearOreillyCredentials()
        _oreillyConfig.value = repository.oreillyAccessConfig()
        _status.value = "Cleared saved library card + PIN."
    }
}

internal fun ReaderViewModel.addOreillyBook(bookId: String, title: String) {
    viewModelScope.launch {
        val key = repository.addOreillyBook(bookId, title)
        repository.markOpened(key)
        _oreillySession.value = repository.oreillySession(key)
        repository.beginReaderContext(key)
        startReadingSession(key)
    }
}

/** Open the O'Reilly catalog for browsing (proxied through your library, with your saved card/PIN). */
internal fun ReaderViewModel.browseOreilly() {
    viewModelScope.launch { _oreillyCatalog.value = repository.oreillyCatalog() }
}

/** Leave the O'Reilly catalog without opening anything. */
internal fun ReaderViewModel.closeOreillyCatalog() { _oreillyCatalog.value = null }

/**
 * Open a book tapped in the O'Reilly catalog: register it read-in-place (deduped by id, so
 * re-browsing the same book reuses its library entry + notes) and hand off to the reader. This is
 * the O'Reilly counterpart to [openRoyalRoad] — browse, tap, and you're reading.
 */
internal fun ReaderViewModel.openOreillyFromCatalog(bookId: String, title: String) {
    viewModelScope.launch {
        val key = repository.addOreillyBook(bookId, title)
        repository.markOpened(key)
        _oreillyCatalog.value = null
        _oreillySession.value = repository.oreillySession(key)
        repository.beginReaderContext(key)
        startReadingSession(key)
    }
}

/** Capture a page-anchored note on the open PDF (quote located by the reader on that page). */
internal fun ReaderViewModel.capturePdfNote(page: Int, quote: String, body: String) {
    val session = _pdfSession.value ?: return
    viewModelScope.launch {
        val note = repository.capturePdfNote(session.bookKey, page, quote, body)
        _status.value = "Note ${note.key} captured on page ${page + 1}."
    }
}

internal fun ReaderViewModel.closePdf() { endReadingSession(); _pdfSession.value = null }

internal fun ReaderViewModel.closeOreilly() {
    _oreillySession.value?.bookKey?.let { repository.endReaderContext(it) }
    endReadingSession()
    _oreillySession.value = null
}

/** Persist the O'Reilly reader's position so the next open lands one tap from your spot. */
internal fun ReaderViewModel.saveOreillyPosition(location: String) {
    onReadingProgress()
    val session = _oreillySession.value ?: return
    viewModelScope.launch { repository.saveExternalPosition(session.bookKey, location) }
}

/** Capture a note on the open O'Reilly book — your layer only (quote + location token). */
internal fun ReaderViewModel.captureOreillyNote(location: String, quote: String, body: String) {
    val session = _oreillySession.value ?: return
    viewModelScope.launch {
        val note = repository.captureExternalNote(session.bookKey, location, quote, body)
        _status.value = "Note ${note.key} captured on O'Reilly book."
    }
}
