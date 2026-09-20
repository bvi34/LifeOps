package com.citation.app.ui

import com.citation.core.reader.BookSearch

/**
 * Searching inside the open book.
 */

internal fun ReaderViewModel.openSearch() { _searchOpen.value = true }

internal fun ReaderViewModel.closeSearch() {
    _searchOpen.value = false
    _searchQuery.value = ""
    _searchHits.value = emptyList()
}

/**
 * Run a search over the open book. Synchronous over the in-memory book — one person's book is
 * small, so there is no index to build and no reason to make the caller wait on a coroutine.
 */
internal fun ReaderViewModel.search(query: String) {
    _searchQuery.value = query
    val book = _openBook.value
    _searchHits.value = if (book == null) emptyList() else BookSearch.search(book, query)
}

/** Land on a hit: its chapter, at its offset, with the match still lit. */
internal fun ReaderViewModel.goToHit(hit: BookSearch.Hit) {
    // A hit's offset is a canonical character offset, so it is staged on the channel that means
    // that in both reading modes rather than the one the scrolling reader treats as pixels.
    goToPlace(hit.chapterOrdinal, hit.offset, remember = true)
}
