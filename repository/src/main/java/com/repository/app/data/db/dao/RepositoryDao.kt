package com.repository.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.repository.app.data.db.entities.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Every document Repository holds, and the four questions ever asked of them: all of them, one of
 * them, the ones on a record, and the ones an app filed.
 *
 * There is no search query here. Searching is `logic/Shelf`, over rows already in memory, because
 * the shelf is a household's paperwork rather than a corpus — a few hundred rows — and a SQL `LIKE`
 * could not search the documents other apps are lending anyway. One search that covers everything
 * beats a faster one that covers half.
 */
@Dao
interface RepositoryDao {

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE ownerApp = :appKey AND ownerKey = :recordKey ORDER BY addedAt DESC")
    fun observeOn(appKey: String, recordKey: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM documents")
    suspend fun allDocuments(): List<DocumentEntity>

    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    /**
     * Rename every document filed against one record.
     *
     * The owning app calls this when the thing is renamed, which is the whole maintenance cost of
     * carrying a label instead of a foreign key — and much cheaper than this module knowing what an
     * asset is.
     */
    @Query("UPDATE documents SET ownerLabel = :label, updatedAt = :at WHERE ownerApp = :appKey AND ownerKey = :recordKey")
    suspend fun relabel(appKey: String, recordKey: String, label: String, at: Long)

    /** Everything an app filed — for the day that app is uninstalled, or asks what it owns. */
    @Query("SELECT * FROM documents WHERE ownerApp = :appKey ORDER BY addedAt DESC")
    suspend fun filedBy(appKey: String): List<DocumentEntity>
}
