package com.citation.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Royal Road catalog state. The chapter *bodies* live in the disposable file store (borrowed cache);
 * only the catalog and each chapter's cached-flag live here, so the buffer/backfill planners can
 * reason about "what's cached" and the eviction job can find stale non-favourites — all without
 * loading text. Favourite state and the last-read clock (which eviction reads) live on
 * [RrFictionEntity].
 */
@Entity(tableName = "rr_fictions")
data class RrFictionEntity(
    @androidx.room.PrimaryKey val fictionId: Long,
    val title: String,
    val isFavorite: Boolean = false,
    val currentOrdinal: Int = 0,
    val expectedCount: Int = 0,
    val lastReadAt: Long = 0,
    // The sovereign BookEntity key this fiction is registered under, so notes/highlights on an RR
    // serial live in the sovereign store and survive eviction of its (disposable) chapter bodies.
    val bookKey: String? = null
)

@Entity(tableName = "rr_chapters", primaryKeys = ["fictionId", "ordinal"])
data class RrChapterMetaEntity(
    val fictionId: Long,
    val ordinal: Int,
    val chapterId: Long,
    val title: String,
    val url: String,
    val cached: Boolean = false
)

@Dao
interface RoyalRoadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiction(fiction: RrFictionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<RrChapterMetaEntity>)

    @Query("SELECT * FROM rr_fictions WHERE fictionId = :id")
    suspend fun fiction(id: Long): RrFictionEntity?

    @Query("SELECT * FROM rr_fictions")
    suspend fun allFictions(): List<RrFictionEntity>

    @Query("SELECT * FROM rr_chapters WHERE fictionId = :id ORDER BY ordinal ASC")
    suspend fun chapters(id: Long): List<RrChapterMetaEntity>

    @Query("SELECT ordinal FROM rr_chapters WHERE fictionId = :id AND cached = 1")
    suspend fun cachedOrdinals(id: Long): List<Int>

    @Query("UPDATE rr_chapters SET cached = :cached WHERE fictionId = :id AND ordinal = :ordinal")
    suspend fun setCached(id: Long, ordinal: Int, cached: Boolean)

    @Query("UPDATE rr_fictions SET isFavorite = :fav WHERE fictionId = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE rr_fictions SET currentOrdinal = :ordinal, lastReadAt = :now WHERE fictionId = :id")
    suspend fun setProgress(id: Long, ordinal: Int, now: Long)

    @Query("UPDATE rr_fictions SET expectedCount = :count WHERE fictionId = :id")
    suspend fun setExpectedCount(id: Long, count: Int)

    @Query("UPDATE rr_fictions SET bookKey = :bookKey WHERE fictionId = :id")
    suspend fun setBookKey(id: Long, bookKey: String)

    @Query("DELETE FROM rr_chapters WHERE fictionId = :id")
    suspend fun deleteChapters(id: Long)
}
