package com.citation.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for Citation's **sovereign** store — the metadata, notes, highlights, manifests, and
 * sync bookkeeping that must never be auto-evicted. Borrowed chapter *bodies* (Royal Road) live in a
 * separate disposable file store (see [com.citation.app.data.store.FileStores]); only their cache
 * facts are mirrored into [ChapterEntity] rows here so the integrity manifest can reason about them
 * without loading text.
 *
 * Every primary key is a stringified [com.citation.core.key.EntityKey] (`ER-Book-3`), so provenance
 * is baked into the row id and the sync seam can address records directly.
 */

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val key: String,
    val title: String,
    val author: String?,
    val sourceType: String,
    val sourceId: String?,
    val language: String?,
    // Two orthogonal state machines, stored as independent columns (never collapsed).
    val acquisitionState: String,
    val readingState: String,
    // Reader position restore.
    val lastChapterOrdinal: Int = 0,
    val lastCharOffset: Int = 0,
    // Read-in-place (O'Reilly) position token — the source reader's own opaque location.
    val externalLocation: String? = null,
    val isFavorite: Boolean = false,
    // Wall-clock of the last time this book was opened in a reader, so the Read tab can resume the
    // most recent thing where you left off. Null until first opened.
    val lastOpenedAt: Long? = null,
    val createdAt: Long
)

/**
 * A chapter row. For owned books the [text] is stored inline (sovereign). For borrowed serials the
 * text lives in the disposable file store and [text] is null here — the row still records [byteSize]
 * and presence so the manifest can detect gaps and account size without the body.
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookKey", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["key"],
        childColumns = ["bookKey"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookKey")]
)
data class ChapterEntity(
    val bookKey: String,
    val ordinal: Int,
    val title: String,
    val sourceRef: String,
    val text: String?,
    val byteSize: Long,
    val cachedAt: Long
)

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["key"],
        childColumns = ["bookKey"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("bookKey")]
)
data class HighlightEntity(
    @PrimaryKey val key: String,
    // Nullable so a highlight *outlives* its source: eviction/deletion of the book orphans, not deletes.
    val bookKey: String?,
    val sourceType: String,
    val sourceId: String?,
    val frozenTitle: String,
    val frozenAuthor: String?,
    val quotedSnapshot: String,
    // Typed anchor, serialized (JSON). Kept opaque to Room; decoded by the mapper.
    val anchorJson: String,
    val createdAt: Long
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["key"],
        childColumns = ["bookKey"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("bookKey")]
)
data class NoteEntity(
    @PrimaryKey val key: String,
    val bookKey: String?,
    val type: String,
    val body: String,
    val sourceType: String,
    val sourceId: String?,
    val frozenTitle: String,
    val frozenAuthor: String?,
    // References (snapshot + anchor) serialized as JSON; a passage-anchored note has exactly one.
    val referencesJson: String,
    val createdAt: Long,
    // Outbound sync: the monotonic version this note was posted at (null = not yet queued).
    val syncVersion: Long? = null,
    // Free-form organizational tags, serialized as a JSON string array. A local retrieval layer
    // (search/facet/group); deliberately not on the sync wire. Defaults to an empty array.
    val tagsJson: String = "[]"
)

/** Per-app key allocator high-water marks, so minting resumes without gaps across restarts. */
@Entity(tableName = "key_watermarks")
data class KeyWatermarkEntity(
    @PrimaryKey val type: String,
    val highWater: Long
)

/** The single row holding mailbox bookkeeping (out version + inbox cursor). */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val outVersion: Long,
    val inboxCursor: Long
)
