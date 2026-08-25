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
    // --- Shelf metadata. All nullable/defaulted: a source that doesn't state it isn't wrong, and
    // every one of these is recoverable by re-parsing the stored file, so none of it is sovereign
    // in the way a note is.
    val publisher: String? = null,
    val published: String? = null,
    val description: String? = null,
    /** Subjects/genres as a JSON string array (`AnchorCodec`-style opaque column). */
    val subjectsJson: String = "[]",
    val series: String? = null,
    val seriesIndex: Float? = null,
    /** Relative path of the extracted cover under the sovereign store, or null if the book has none. */
    val coverPath: String? = null,
    /** How many chapters the book has, so the library can show progress without loading them. */
    val chapterCount: Int = 0,
    /** The publisher's nested contents, serialized by `BlockCodec`; empty for sources without one. */
    val tocJson: String? = null,
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
    val cachedAt: Long,
    /**
     * The structured view over [text] — headings, quotes, verse, images — as ranges into those same
     * characters (see `BlockCodec`). Derived data: null or unreadable simply means the reader sets
     * the chapter as plain paragraphs, exactly as it did before structure existed.
     */
    val blocksJson: String? = null,
    /** Element id to offset, for landing a footnote or contents link inside this chapter. */
    val anchorsJson: String? = null
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

/**
 * A shelf the user made. Manual rather than rule-based on purpose — see
 * [com.citation.core.library.BookCollection].
 */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    val createdAt: Long
)

/**
 * A book's place on a shelf. Cascades from both sides: deleting the book or the shelf removes the
 * membership, never leaving a row pointing at nothing.
 */
@Entity(
    tableName = "collection_members",
    primaryKeys = ["collectionId", "bookKey"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["key"],
            childColumns = ["bookKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookKey"), Index("collectionId")]
)
data class CollectionMemberEntity(
    val collectionId: String,
    val bookKey: String,
    val addedAt: Long
)

/**
 * A saved OPDS catalog.
 *
 * Only the identity and address live here. Credentials do not: the username and password go to the
 * same Keystore-backed encrypted store the O'Reilly library card uses, so a database copied out of
 * a backup carries no way into anyone's server.
 */
@Entity(tableName = "opds_catalogs")
data class OpdsCatalogEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val position: Int,
    val createdAt: Long,
    /** Wall clock of the last successful fetch, for showing which catalogs are reachable. */
    val lastOpenedAt: Long? = null
)
