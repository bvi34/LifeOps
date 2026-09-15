package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.DocumentEntity
import com.health.app.data.model.Document
import com.health.app.logic.DocumentKind
import com.health.app.logic.Documents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Scanned paperwork: the rows that name the files, never the files themselves.
 *
 * Disk is the host app's, reached through [onDocumentDiscarded], so this class stays JVM-testable
 * for the same reason the rest of the package does.
 */
class DocumentStore(
    private val dao: HealthDao,
    private val onDocumentDiscarded: (fileName: String) -> Unit
) {

    fun observeDocuments(profileId: String): Flow<List<Document>> =
        dao.observeDocuments(profileId).map { rows -> sortDocuments(rows.map { it.toModel() }) }

    /** The paperwork that belongs to the house rather than to anybody in it. */
    fun observeHouseholdDocuments(): Flow<List<Document>> =
        dao.observeHouseholdDocuments().map { rows -> sortDocuments(rows.map { it.toModel() }) }

    suspend fun getDocument(id: String): Document? = dao.getDocument(id)?.toModel()

    /**
     * Every document in Health, with the name of whoever it is about attached.
     *
     * For the suite's shelf and nothing else — see `shelf/HealthDocumentSource`. The name is resolved
     * here rather than there because a profile id means nothing outside this app, and "Sam" is the
     * only part of a lab result the shelf has any business showing.
     */
    fun observeAllDocumentsWithOwner(): Flow<List<Pair<Document, String?>>> =
        combine(dao.observeAllDocuments(), dao.observeAllProfiles()) { documents, profiles ->
            val names = profiles.associate { it.id to it.name }
            sortDocuments(documents.map { it.toModel() }).map { document ->
                document to document.profileId?.let { names[it] }
            }
        }

    private fun sortDocuments(documents: List<Document>): List<Document> =
        Documents.sort(documents, date = { it.documentDate }, title = { it.title })

    /**
     * File a document that has already been copied into the store.
     *
     * The bytes are moved first and the row is written second, deliberately. The other order leaves
     * a window in which a row names a file that does not exist yet — and the screen that renders it
     * in that window shows a document the household does not actually have.
     */
    suspend fun addDocument(
        title: String,
        kind: DocumentKind,
        fileName: String,
        profileId: String? = null,
        documentDate: String? = null,
        mimeType: String? = null,
        sizeBytes: Long? = null,
        episodeId: String? = null,
        conditionId: String? = null,
        immunizationId: String? = null,
        providerId: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertDocument(
            DocumentEntity(
                id = id,
                profileId = profileId.clean(),
                title = title.trim().ifBlank { kind.label },
                kind = kind.key,
                documentDate = documentDate.clean(),
                fileName = fileName,
                mimeType = mimeType.clean(),
                sizeBytes = sizeBytes,
                episodeId = episodeId.clean(),
                conditionId = conditionId.clean(),
                immunizationId = immunizationId.clean(),
                providerId = providerId.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    /**
     * Edit what was typed about a document. The file itself is never touched here — re-filing an
     * after-visit summary under the right child does not change the PDF.
     */
    suspend fun updateDocument(document: Document) {
        val existing = dao.getDocument(document.id) ?: return
        dao.upsertDocument(
            existing.copy(
                profileId = document.profileId.clean(),
                title = document.title.trim().ifBlank { document.kind.label },
                kind = document.kind.key,
                documentDate = document.documentDate.clean(),
                episodeId = document.episodeId.clean(),
                conditionId = document.conditionId.clean(),
                immunizationId = document.immunizationId.clean(),
                providerId = document.providerId.clean(),
                note = document.note.clean(),
                updatedAt = now()
            )
        )
    }

    /** Drop a document, and then the file behind it — in that order, never the other way round. */
    suspend fun deleteDocument(id: String) {
        val existing = dao.getDocument(id) ?: return
        dao.deleteDocumentRow(id)
        onDocumentDiscarded(existing.fileName)
    }
}
