package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.BookSummary
import com.citation.app.data.acquire
import com.citation.app.data.bookAsset
import com.citation.app.data.resolveNote
import com.citation.app.data.storageReport
import com.citation.app.data.sync
import com.citation.core.model.TocEntry
import com.citation.core.note.Note
import com.citation.core.reader.ReadingPace
import com.citation.core.reader.ReadingProgress
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Moving around: closing a book, deleting one, going to a chapter or a table-of-contents entry,
 * and saving where you got to.
 */

internal fun ReaderViewModel.closeBook() {
    endReadingSession()
    openRrFictionId = null
    _openBook.value = null
    // A turn that never landed must not be inherited by the next book opened at that ordinal.
    pendingScrollChapter = -1
    pendingScrollOffset = 0
    pendingCanonicalChapter = -1
    pendingCanonicalOffset = 0
    pendingEndChapter = -1
    closeSearch()
    _perBookSettings.value = false
    _position.value = 0 to 0
    _pace.value = ReadingPace()
}

/**
 * Remove a book from the library. For a Royal Road serial this also un-favourites it and drops its
 * cached chapter bodies (the "uncache" the user wants); if it happens to be the one open in the
 * reader, the reader is closed too. Notes on it are kept (they hold their own frozen snapshots).
 */
internal fun ReaderViewModel.deleteBook(book: BookSummary) = deleteBook(book.key, book.title)

/** Remove a book by key, for surfaces that hold a library entry rather than a summary. */
internal fun ReaderViewModel.deleteBook(bookKey: String, title: String) {
    viewModelScope.launch {
        repository.deleteBook(bookKey)
        if (_openBook.value?.key?.toString() == bookKey) closeBook()
        _status.value = "Removed “$title”."
    }
}

internal fun ReaderViewModel.goToChapter(ordinal: Int) {
    val book = _openBook.value ?: return
    val target = ordinal.coerceIn(0, book.chapters.lastIndex)
    // Read now, not from inside the coroutine below: by then the reader may already have
    // consumed the intent, and the save it guards would fire on a chapter it no longer describes.
    val landsElsewhere = hasLandingIntent(target)
    onReadingProgress() // a chapter advance is genuine reading progress
    _chapterOrdinal.value = target
    viewModelScope.launch {
        val rr = openRrFictionId
        if (rr != null) {
            // Fetch the target chapter + slide the prefetch buffer, then re-read the book so the
            // freshly-cached bodies replace their "Fetching…" placeholders. Without this refresh
            // the reader would keep the stale snapshot captured at open time and reading would
            // dead-end at the initially-buffered window.
            repository.royalRoad.advance(rr, target)
            _openBook.value = repository.royalRoad.loadBook(rr).copy(key = book.key)
        } else if (!landsElsewhere) {
            // Owned books (EPUB / PDF / AO3 snapshot) already hold every chapter inline. Skipped
            // when the reader is about to land somewhere other than the chapter's first word:
            // writing offset 0 here would be a lie for the moment before the reader saves the
            // real one, and an app killed inside that moment would reopen at the wrong place.
            book.key?.let { repository.savePosition(it.toString(), target, 0) }
        }
    }
}

/**
 * Turn back *into* [ordinal] from the first page of the chapter after it. Same navigation as
 * [goToChapter], except the reader opens the chapter at its **last** page: a page back is one
 * page, so crossing a chapter boundary backwards must not skip the chapter you are turning into.
 * Explicit chapter jumps (the Previous button, the contents list) still use [goToChapter] and
 * open at the beginning — that is what those mean.
 */
internal fun ReaderViewModel.goToChapterEnd(ordinal: Int) {
    val book = _openBook.value ?: return
    val target = ordinal.coerceIn(0, book.chapters.lastIndex)
    // A landing at the end and a restored offset are mutually exclusive; the newer intent wins.
    pendingScrollChapter = -1
    pendingScrollOffset = 0
    pendingCanonicalChapter = -1
    pendingCanonicalOffset = 0
    pendingEndChapter = target
    goToChapter(target)
}

/** True when the reader is arriving somewhere other than [target]'s first word. */
internal fun ReaderViewModel.hasLandingIntent(target: Int): Boolean =
    pendingEndChapter == target || (pendingScrollChapter == target && pendingScrollOffset > 0)

/** The stored file behind an illustration reference, or null when it was not kept. */
internal fun ReaderViewModel.bookAsset(bookKey: String, src: String): java.io.File? = repository.bookAsset(bookKey, src)

/**
 * Follow a contents entry. An entry that points inside a chapter (`#fragment`) lands on that
 * spot rather than at the chapter's first word — which is the whole reason a single-file book,
 * or a reference work whose spine is a hundred undifferentiated documents, gets a usable
 * contents list at all.
 */
internal fun ReaderViewModel.goToTocEntry(entry: TocEntry) {
    val ordinal = entry.chapterOrdinal ?: return
    val book = _openBook.value ?: return
    val offset = entry.fragment?.let { book.chapterAt(ordinal)?.anchors?.get(it) } ?: 0
    if (offset > 0) {
        pendingScrollChapter = ordinal
        pendingScrollOffset = offset
    }
    goToChapter(ordinal)
}

/** First index of [sub] in [text] closest to [near]; −1 if absent. */
internal fun ReaderViewModel.nearestIndexOf(text: String, sub: String, near: Int): Int {
    if (sub.isEmpty()) return -1
    var from = 0
    var best = -1
    var bestDist = Long.MAX_VALUE
    while (true) {
        val i = text.indexOf(sub, from)
        if (i < 0) break
        val dist = abs(i - near).toLong()
        if (dist < bestDist) { bestDist = dist; best = i }
        from = i + 1
    }
    return best
}

/** Persist the reader's live position (current chapter + in-chapter scroll offset in px). */
internal fun ReaderViewModel.savePosition(chapterOrdinal: Int, charOffset: Int) {
    onReadingProgress() // scrolling/paging within a chapter is reading progress
    val book = _openBook.value ?: return
    val key = book.key?.toString() ?: return
    // Record how far through the book this is, so the library quotes the same number the page
    // does. Only the paged reader passes a canonical character offset here; scroll mode's
    // pixel offset would measure nothing, so it reports through the live position instead.
    val measured = _position.value
        .takeIf { it.first == chapterOrdinal }
        ?.let { ReadingProgress.at(book, it.first, it.second).fraction }
    viewModelScope.launch { repository.savePosition(key, chapterOrdinal, charOffset, measured) }
}

/**
 * The one-time scroll offset to restore for [chapter], or 0 if there is none for it. Cleared on
 * read so turning the page doesn't snap you back to the resumed spot.
 */
/**
 * The one-time **canonical** offset to restore for [chapter], or -1 when there is none.
 *
 * Separate from [consumePendingScroll] because the unit is different and only the reader can
 * resolve it: the paged body turns it into a page, the scrolling body into a line's position.
 * Cleared on read, like the others.
 */
internal fun ReaderViewModel.consumePendingCanonical(chapter: Int): Int =
    if (chapter == pendingCanonicalChapter) {
        pendingCanonicalChapter = -1
        pendingCanonicalOffset.also { pendingCanonicalOffset = 0 }
    } else -1

internal fun ReaderViewModel.consumePendingScroll(chapter: Int): Int =
    if (chapter == pendingScrollChapter && pendingScrollOffset > 0) {
        pendingScrollChapter = -1
        pendingScrollOffset.also { pendingScrollOffset = 0 }
    } else 0

/**
 * Whether [chapter] is being entered backwards and so should open at its last page (paged) or
 * scrolled to its end (scroll). Cleared on read, like [consumePendingScroll], so turning forward
 * again doesn't keep snapping to the end.
 */
internal fun ReaderViewModel.consumePendingEnd(chapter: Int): Boolean =
    (chapter == pendingEndChapter).also { if (it) pendingEndChapter = -1 }

/**
 * Jump from a note back to its live context: open the source book and land on the resolved
 * chapter. Best-effort for borrowed sources — an orphaned/unavailable note just opens the book.
 */
internal fun ReaderViewModel.jumpToNote(note: Note) {
    val bookKey = note.source.bookKey?.toString() ?: return
    viewModelScope.launch {
        val result = repository.openBook(bookKey) ?: run {
            _status.value = "That source is no longer available — the note still holds its snapshot."
            return@launch
        }
        openRrFictionId = result.rrFictionId
        repository.markOpened(bookKey)
        _openBook.value = result.book
        startReadingSession(bookKey)
        val target = repository.resolveNote(note).firstOrNull { it.chapterOrdinal != null && it.state.canJump }
        _chapterOrdinal.value = target?.chapterOrdinal ?: 0
        if (target == null) {
            _status.value = "Passage not found in the current text — opened the book at the start."
        }
    }
}

/** Load the storage report for the visibility screen (per-item + aggregate, recoverability-tagged). */
internal suspend fun ReaderViewModel.storageReport(): com.citation.core.manifest.StorageReport = repository.storageReport()

/** Run a sync round with LifeOps on demand (drain outbox, consume acquire intents). */
internal fun ReaderViewModel.sync() {
    viewModelScope.launch {
        _status.value = "Syncing with LifeOps…"
        runCatching { repository.sync() }
            .onSuccess { s ->
                _status.value = "Synced: ${s.sent} packet(s) up" +
                    if (s.intentsCreated > 0) ", ${s.intentsCreated} book(s) added to wanted" else ""
            }
            .onFailure { _status.value = "Sync failed — will retry in the background." }
    }
}

internal fun ReaderViewModel.clearStatus() { _status.value = null }

/** Dismisses the import alert once the user has read it. */
internal fun ReaderViewModel.dismissImportAlert() { _importAlert.value = null }

/**
 * Reports an import that failed on its way in from another app — a file that couldn't be read,
 * or one that turned out to be neither an EPUB nor a PDF. This can't ride the [status] line: a
 * file opened from outside lands the user in the reader, and status is only drawn on the New and
 * Settings tabs, so a failure there would be invisible. The alert is surfaced over whatever is
 * on screen instead.
 */
internal fun ReaderViewModel.reportImportProblem(message: String) { _importAlert.value = message }
