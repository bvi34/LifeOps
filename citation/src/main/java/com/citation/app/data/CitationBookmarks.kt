package com.citation.app.data

import com.citation.app.data.db.BookmarkEntity
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.model.Book
import com.citation.core.reader.Bookmark
import com.citation.core.reader.Bookmarks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Bookmarks: a named place in a book, and getting back to it.
 */

/** Every bookmark in a book, in reading order. */
internal fun CitationRepository.bookmarks(bookKey: String): Flow<List<Bookmark>> =
    db.bookmarkDao().observeForBook(bookKey).map { rows -> rows.map { it.toBookmark() } }

internal fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
    key = EntityKey.parse(key)!!,
    bookKey = bookKey?.let { EntityKey.parse(it) },
    chapterOrdinal = chapterOrdinal,
    charOffset = charOffset,
    snippet = snippet,
    chapterTitle = chapterTitle,
    label = label,
    createdAt = createdAt
)

/**
 * Save a place, or return the one already saved there.
 *
 * Setting a bookmark twice on the same page means moving its label, not making a second mark,
 * so a position within a screenful of an existing bookmark reuses it. That is what lets one
 * control in the reader be a toggle rather than a way to accumulate near-identical rows.
 */
internal suspend fun CitationRepository.addBookmark(
    book: Book,
    chapterOrdinal: Int,
    charOffset: Int,
    label: String? = null,
    now: Long = System.currentTimeMillis()
): Bookmark {
    val bookKey = book.key ?: error("cannot bookmark an unkeyed book")
    val existing = Bookmarks.existingAt(
        db.bookmarkDao().forBook(bookKey.toString()).map { it.toBookmark() },
        chapterOrdinal,
        charOffset
    )
    if (existing != null) {
        if (label != null) db.bookmarkDao().setLabel(existing.key.toString(), label.trim().takeIf { it.isNotBlank() })
        return existing
    }

    val bookmark = Bookmarks.at(
        key = keys.next(EntityType.BOOKMARK),
        bookKey = bookKey,
        book = book,
        chapterOrdinal = chapterOrdinal,
        charOffset = charOffset,
        label = label,
        now = now
    )
    db.bookmarkDao().upsert(
        BookmarkEntity(
            key = bookmark.key.toString(),
            bookKey = bookKey.toString(),
            chapterOrdinal = bookmark.chapterOrdinal,
            charOffset = bookmark.charOffset,
            snippet = bookmark.snippet,
            chapterTitle = bookmark.chapterTitle,
            label = bookmark.label,
            createdAt = bookmark.createdAt
        )
    )
    checkpointKey(EntityType.BOOKMARK)
    return bookmark
}

internal suspend fun CitationRepository.deleteBookmark(key: String) = db.bookmarkDao().delete(key)

internal suspend fun CitationRepository.setBookmarkLabel(key: String, label: String?) =
    db.bookmarkDao().setLabel(key, label?.trim()?.takeIf { it.isNotBlank() })
