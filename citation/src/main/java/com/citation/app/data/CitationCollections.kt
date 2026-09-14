package com.citation.app.data

import com.citation.app.data.db.CollectionEntity
import com.citation.app.data.db.CollectionMemberEntity
import com.citation.core.library.BookCollection
import com.citation.core.sync.ReadingState
import kotlinx.coroutines.flow.map

/**
 * Collections: the shelves a reader sorts their own library onto.
 */

internal suspend fun CitationRepository.createCollection(name: String, now: Long = System.currentTimeMillis()): BookCollection {
    val trimmed = name.trim().ifBlank { "Untitled shelf" }
    val existing = db.collectionDao().all()
    val collection = CollectionEntity(
        id = "COL-" + now.toString(36) + "-" + (existing.size + 1),
        name = trimmed,
        position = existing.size,
        createdAt = now
    )
    db.collectionDao().upsert(collection)
    return BookCollection(collection.id, collection.name, collection.position)
}

internal suspend fun CitationRepository.renameCollection(id: String, name: String) {
    name.trim().takeIf { it.isNotBlank() }?.let { db.collectionDao().rename(id, it) }
}

/** Remove a shelf. The books on it are untouched — a shelf is a view, not a container. */
internal suspend fun CitationRepository.deleteCollection(id: String) = db.collectionDao().delete(id)

internal suspend fun CitationRepository.addToCollection(collectionId: String, bookKey: String, now: Long = System.currentTimeMillis()) {
    db.collectionDao().addMember(CollectionMemberEntity(collectionId, bookKey, now))
}

internal suspend fun CitationRepository.removeFromCollection(collectionId: String, bookKey: String) {
    db.collectionDao().removeMember(collectionId, bookKey)
}

/** Which shelves a book is on, for the book detail sheet's checklist. */
internal suspend fun CitationRepository.collectionsOf(bookKey: String): Set<String> =
    db.collectionDao().membershipsOf(bookKey).map { it.collectionId }.toSet()

internal suspend fun CitationRepository.setFavorite(bookKey: String, favorite: Boolean) =
    db.bookDao().setFavorite(bookKey, favorite)

/** Mark a book finished (or put it back on the pile) from the library, without opening it. */
internal suspend fun CitationRepository.setReadingState(bookKey: String, state: ReadingState) =
    db.bookDao().setReadingState(bookKey, state.name)

/** The cover file for a book, or null — the library screen decodes it directly. */
internal fun CitationRepository.coverFile(bookKey: String): java.io.File? = files.coverFile(bookKey)

/** The stored image behind a chapter's illustration reference, or null if it was not kept. */
internal fun CitationRepository.bookAsset(bookKey: String, src: String): java.io.File? = files.readBookAsset(bookKey, src)
