package com.citation.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Archive of Our Own catalog state — the AO3 mirror of [RrFictionEntity]/[RrChapterMetaEntity]. The
 * chapter *bodies* live in the disposable file store (borrowed cache, under an `ao3-{workId}`
 * namespace so an AO3 work and a Royal Road fiction sharing a numeric id never collide); only the
 * catalog and each chapter's cached-flag live here, so the buffer/backfill planners can reason about
 * "what's cached" and the eviction job can find stale non-favourites — all without loading text.
 * Favourite state and the last-read clock (which eviction reads) live on [Ao3WorkEntity].
 */
@Entity(tableName = "ao3_works")
data class Ao3WorkEntity(
    @androidx.room.PrimaryKey val workId: Long,
    val title: String,
    val author: String? = null,
    val isFavorite: Boolean = false,
    val currentOrdinal: Int = 0,
    val expectedCount: Int = 0,
    val lastReadAt: Long = 0,
    // The sovereign BookEntity key this work is registered under, so notes/highlights on an AO3 work
    // live in the sovereign store and survive eviction of its (disposable) chapter bodies.
    val bookKey: String? = null
)

@Entity(tableName = "ao3_chapters", primaryKeys = ["workId", "ordinal"])
data class Ao3ChapterMetaEntity(
    val workId: Long,
    val ordinal: Int,
    val chapterId: Long,
    val title: String,
    val url: String,
    val cached: Boolean = false
)

@Dao
interface Ao3Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: Ao3WorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<Ao3ChapterMetaEntity>)

    @Query("SELECT * FROM ao3_works WHERE workId = :id")
    suspend fun work(id: Long): Ao3WorkEntity?

    @Query("SELECT * FROM ao3_works")
    suspend fun allWorks(): List<Ao3WorkEntity>

    @Query("SELECT * FROM ao3_chapters WHERE workId = :id ORDER BY ordinal ASC")
    suspend fun chapters(id: Long): List<Ao3ChapterMetaEntity>

    @Query("SELECT ordinal FROM ao3_chapters WHERE workId = :id AND cached = 1")
    suspend fun cachedOrdinals(id: Long): List<Int>

    @Query("UPDATE ao3_chapters SET cached = :cached WHERE workId = :id AND ordinal = :ordinal")
    suspend fun setCached(id: Long, ordinal: Int, cached: Boolean)

    @Query("UPDATE ao3_works SET isFavorite = :fav WHERE workId = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE ao3_works SET currentOrdinal = :ordinal, lastReadAt = :now WHERE workId = :id")
    suspend fun setProgress(id: Long, ordinal: Int, now: Long)

    @Query("UPDATE ao3_works SET expectedCount = :count WHERE workId = :id")
    suspend fun setExpectedCount(id: Long, count: Int)

    @Query("UPDATE ao3_works SET bookKey = :bookKey WHERE workId = :id")
    suspend fun setBookKey(id: Long, bookKey: String)

    @Query("DELETE FROM ao3_chapters WHERE workId = :id")
    suspend fun deleteChapters(id: Long)

    @Query("DELETE FROM ao3_works WHERE workId = :id")
    suspend fun deleteWork(id: Long)
}
