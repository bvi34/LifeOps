package com.citation.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE key = :key")
    suspend fun get(key: String): BookEntity?

    @Query("UPDATE books SET lastChapterOrdinal = :ordinal, lastCharOffset = :offset WHERE key = :key")
    suspend fun savePosition(key: String, ordinal: Int, offset: Int)

    /** Stamp the book as just-opened, so the Read tab can resume the most recent one. */
    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE key = :key")
    suspend fun touchOpened(key: String, openedAt: Long)

    /** The most recently opened book (the Read tab's "pick up where you left off"), or null. */
    @Query("SELECT * FROM books WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT 1")
    fun observeLastOpened(): Flow<BookEntity?>

    @Query("UPDATE books SET externalLocation = :location WHERE key = :key")
    suspend fun saveExternalLocation(key: String, location: String)

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND sourceType = :sourceType LIMIT 1")
    suspend fun findBySource(sourceId: String, sourceType: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    /** Remove a library entry entirely (used when the user deletes a book). */
    @Query("DELETE FROM books WHERE key = :key")
    suspend fun delete(key: String)

    /**
     * Recompute a book's chapter count from the chapters it actually has.
     *
     * The count is denormalised onto the book so the library can show progress for hundreds of
     * books without loading a chapter, which means it has to be refreshed wherever chapters change
     * — a PDF reflow, a serial's catalog growing. Cheaper and less fragile than keeping a running
     * total in each of those places.
     */
    @Query("UPDATE books SET chapterCount = (SELECT COUNT(*) FROM chapters WHERE bookKey = :key) WHERE key = :key")
    suspend fun recountChapters(key: String)

    /** Set the chapter count directly, for a serial whose chapters live outside the chapters table. */
    @Query("UPDATE books SET chapterCount = :count WHERE key = :key")
    suspend fun setChapterCount(key: String, count: Int)

    /** Star or unstar a book from the library screen. */
    @Query("UPDATE books SET isFavorite = :favorite WHERE key = :key")
    suspend fun setFavorite(key: String, favorite: Boolean)

    @Query("UPDATE books SET readingState = :state WHERE key = :key")
    suspend fun setReadingState(key: String, state: String)

    /**
     * Record the cover a book's import extracted. Separate from the insert because a cover can also
     * arrive later — from a catalog entry, or a re-parse of the stored file.
     */
    @Query("UPDATE books SET coverPath = :path WHERE key = :key")
    suspend fun setCoverPath(key: String, path: String?)
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: ChapterEntity)

    @Query("SELECT * FROM chapters WHERE bookKey = :bookKey ORDER BY ordinal ASC")
    suspend fun forBook(bookKey: String): List<ChapterEntity>

    @Query("SELECT ordinal FROM chapters WHERE bookKey = :bookKey")
    suspend fun cachedOrdinals(bookKey: String): List<Int>

    @Query("DELETE FROM chapters WHERE bookKey = :bookKey AND ordinal = :ordinal")
    suspend fun evict(bookKey: String, ordinal: Int)

    /** Drop all stored chapters for a book (used when the book is deleted). */
    @Query("DELETE FROM chapters WHERE bookKey = :bookKey")
    suspend fun deleteForBook(bookKey: String)
}

@Dao
interface HighlightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(highlight: HighlightEntity)

    @Query("SELECT * FROM highlights WHERE bookKey = :bookKey ORDER BY createdAt ASC")
    fun observeForBook(bookKey: String): Flow<List<HighlightEntity>>
}

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bookKey = :bookKey ORDER BY createdAt ASC")
    fun observeForBook(bookKey: String): Flow<List<NoteEntity>>

    /** Notes not yet acknowledged by LifeOps (queued in the outbox), for the sync worker. */
    @Query("SELECT * FROM notes WHERE syncVersion IS NOT NULL ORDER BY syncVersion ASC")
    suspend fun pendingSync(): List<NoteEntity>

    /**
     * Clear the outbound queue marker for every note LifeOps has acknowledged (version ≤ [version]),
     * so a later restart doesn't re-seed and resend notes already durably taken by the center.
     */
    @Query("UPDATE notes SET syncVersion = NULL WHERE syncVersion IS NOT NULL AND syncVersion <= :version")
    suspend fun clearSyncVersionThrough(version: Long)

    @Query("SELECT * FROM notes")
    suspend fun getAllSync(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE key = :key")
    suspend fun get(key: String): NoteEntity?

    @Query("DELETE FROM notes WHERE key = :key")
    suspend fun delete(key: String)

    /** Cross-app capture notes (sourceType = CAPTURE), for clustering and triage. */
    @Query("SELECT * FROM notes WHERE sourceType = 'CAPTURE' ORDER BY createdAt DESC")
    suspend fun captures(): List<NoteEntity>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM key_watermarks")
    suspend fun watermarks(): List<KeyWatermarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWatermark(mark: KeyWatermarkEntity)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun syncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSyncState(state: SyncStateEntity)
}

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: CollectionEntity)

    @Query("SELECT * FROM collections ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY position ASC, createdAt ASC")
    suspend fun all(): List<CollectionEntity>

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(member: CollectionMemberEntity)

    @Query("DELETE FROM collection_members WHERE collectionId = :collectionId AND bookKey = :bookKey")
    suspend fun removeMember(collectionId: String, bookKey: String)

    @Query("SELECT * FROM collection_members")
    fun observeMembers(): Flow<List<CollectionMemberEntity>>

    @Query("SELECT * FROM collection_members WHERE bookKey = :bookKey")
    suspend fun membershipsOf(bookKey: String): List<CollectionMemberEntity>

    @Query("SELECT COUNT(*) FROM collection_members WHERE collectionId = :collectionId")
    suspend fun size(collectionId: String): Int
}

@Dao
interface OpdsCatalogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(catalog: OpdsCatalogEntity)

    @Query("SELECT * FROM opds_catalogs ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<OpdsCatalogEntity>>

    @Query("SELECT * FROM opds_catalogs ORDER BY position ASC, createdAt ASC")
    suspend fun all(): List<OpdsCatalogEntity>

    @Query("SELECT * FROM opds_catalogs WHERE id = :id")
    suspend fun get(id: String): OpdsCatalogEntity?

    @Query("DELETE FROM opds_catalogs WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE opds_catalogs SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun touchOpened(id: String, openedAt: Long)

    @Query("SELECT COUNT(*) FROM opds_catalogs")
    suspend fun count(): Int
}

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE bookKey = :bookKey ORDER BY chapterOrdinal ASC, charOffset ASC")
    fun observeForBook(bookKey: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookKey = :bookKey ORDER BY chapterOrdinal ASC, charOffset ASC")
    suspend fun forBook(bookKey: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks WHERE key = :key")
    suspend fun delete(key: String)

    @Query("UPDATE bookmarks SET label = :label WHERE key = :key")
    suspend fun setLabel(key: String, label: String?)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookKey = :bookKey")
    suspend fun countForBook(bookKey: String): Int
}

@Dao
interface ReadingPaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pace: ReadingPaceEntity)

    @Query("SELECT * FROM reading_pace WHERE bookKey = :bookKey")
    suspend fun get(bookKey: String): ReadingPaceEntity?

    @Query("DELETE FROM reading_pace WHERE bookKey = :bookKey")
    suspend fun delete(bookKey: String)
}
