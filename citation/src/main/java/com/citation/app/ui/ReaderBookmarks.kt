package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.addBookmark
import com.citation.app.data.bookmarks
import com.citation.app.data.deleteBookmark
import com.citation.app.data.setBookmarkLabel
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Bookmarks
import kotlinx.coroutines.launch

/**
 * Bookmarks: naming a place in a book and going back to it.
 */

/**
 * Save this place, or remove the one already saved here.
 *
 * [charOffset] comes from the reader's viewport rather than the stored position, so a bookmark
 * marks the page you are looking at rather than the last place a debounced save happened to
 * land.
 */
internal fun ReaderViewModel.toggleBookmark(charOffset: Int) {
    val book = _openBook.value ?: return
    if (book.key == null) return
    viewModelScope.launch {
        val ordinal = _chapterOrdinal.value
        val existing = Bookmarks.existingAt(bookmarks.value, ordinal, charOffset)
        if (existing != null) {
            repository.deleteBookmark(existing.key.toString())
            _status.value = "Bookmark removed."
        } else {
            val saved = repository.addBookmark(book, ordinal, charOffset)
            _status.value = "Bookmarked: ${saved.display.take(60)}"
        }
    }
}

internal fun ReaderViewModel.deleteBookmark(bookmark: Bookmark) {
    viewModelScope.launch { repository.deleteBookmark(bookmark.key.toString()) }
}

internal fun ReaderViewModel.setBookmarkLabel(bookmark: Bookmark, label: String) {
    viewModelScope.launch { repository.setBookmarkLabel(bookmark.key.toString(), label) }
}

/**
 * Go to a bookmark, landing where its frozen line is *now*. A line that has since been deleted
 * opens its chapter rather than jumping to an offset that no longer means anything.
 */
internal fun ReaderViewModel.goToBookmark(bookmark: Bookmark) {
    val book = _openBook.value ?: return
    when (val target = Bookmarks.resolve(bookmark, book)) {
        is Bookmarks.Target.Found -> {
            pendingScrollChapter = target.chapterOrdinal
            pendingScrollOffset = target.charOffset
            goToChapter(target.chapterOrdinal)
            if (!target.exact) _status.value = "That passage was edited — landed as close as possible."
        }
        is Bookmarks.Target.ChapterOnly -> {
            goToChapter(target.chapterOrdinal)
            _status.value = "That passage is gone; opened the chapter instead."
        }
        Bookmarks.Target.Unavailable ->
            _status.value = "That chapter isn’t available yet."
    }
}
