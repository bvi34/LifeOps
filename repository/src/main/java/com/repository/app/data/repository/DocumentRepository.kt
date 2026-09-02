package com.repository.app.data.repository

import android.net.Uri
import com.repository.app.data.db.dao.RepositoryDao
import com.repository.app.data.db.entities.DocumentEntity
import com.repository.app.data.store.DocumentFiles
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import com.repository.app.logic.Shelf
import com.repository.app.source.DocumentSource
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

/**
 * The shelf, as everything else in the suite sees it.
 *
 * Two kinds of document come out of here and only one kind goes in. **Repository's own** rows are
 * stored, edited and deleted here — including the ones an owning app filed, which are ordinary rows
 * with an owner on them. **Foreign** rows arrive from apps that already keep their own paperwork
 * (see `source/DocumentSource`); they are read and opened, never written, because the app that owns
 * them has rules about deleting that a second writer would have to duplicate or would break.
 *
 * Every list this class hands out is already ordered by `logic/Shelf`, so no screen has to remember
 * which end is newest.
 */
class DocumentRepository(
    private val dao: RepositoryDao,
    private val files: DocumentFiles
) {

    // ------------------------------------------------------------------ reading

    /** Repository's own documents. */
    fun observeOwn(): Flow<List<DocumentFacts>> =
        dao.observeDocuments().map { rows -> Shelf.order(rows.map { it.toFacts() }) }

    /**
     * Everything on the shelf: this app's rows and every registered source's, in one list.
     *
     * The combine is over a *list of flows that can itself change*, because an app registers when it
     * starts and the shelf may already be open. `flatMapLatest` re-subscribes when the roster
     * changes, which is the difference between a drawer appearing and a household being told to
     * restart the app.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeShelf(): Flow<List<DocumentFacts>> =
        DocumentSources.all.flatMapLatest { sources ->
            val flows = listOf(observeOwn()) + sources.map { source -> source.lend() }
            combine(flows) { lists -> Shelf.order(lists.toList().flatten()) }
        }

    /** The documents filed against one of an app's records — what an owning app's section shows. */
    fun observeOn(appKey: String, recordKey: String): Flow<List<DocumentFacts>> =
        dao.observeOn(appKey, recordKey).map { rows -> Shelf.order(rows.map { it.toFacts() }) }

    suspend fun get(id: String): DocumentFacts? = dao.getDocument(id)?.toFacts()

    // ------------------------------------------------------------------ filing

    /**
     * File a picked document.
     *
     * The bytes are copied first and the row is written only if that worked, so a failed copy leaves
     * nothing behind rather than a row pointing at a file that was never written. Returns the new
     * document's id, or null when the file could not be read — the caller says so on screen and the
     * form stays open.
     *
     * A blank title falls back to the file's own name rather than to "Untitled": the household knows
     * what `2026-03-statement.pdf` is, and nothing here can do better than what they were given.
     */
    suspend fun file(
        source: Uri,
        title: String,
        kind: DocumentKind,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        note: String? = null,
        addedAt: Long = System.currentTimeMillis()
    ): String? {
        val picked = files.describe(source)
        val stored = files.save(source, picked) ?: return null
        val id = UUID.randomUUID().toString()
        dao.upsertDocument(
            DocumentEntity(
                id = id,
                title = title.trim().ifBlank { Documents.titleFrom(picked.displayName).ifBlank { "Document" } },
                kind = kind.key,
                ownerApp = owner.appKey,
                ownerKey = owner.recordKey,
                ownerLabel = owner.label,
                fileName = stored.fileName,
                mimeType = stored.mimeType,
                sizeBytes = stored.sizeBytes,
                note = note?.trim()?.takeIf { it.isNotBlank() },
                addedAt = addedAt,
                updatedAt = addedAt
            )
        )
        return id
    }

    /** Rename a document, change what kind it is, or move it to another drawer. */
    suspend fun update(
        id: String,
        title: String,
        kind: DocumentKind,
        note: String?,
        owner: DocumentOwner? = null
    ) {
        val existing = dao.getDocument(id) ?: return
        dao.upsertDocument(
            existing.copy(
                title = title.trim().ifBlank { existing.title },
                kind = kind.key,
                note = note?.trim()?.takeIf { it.isNotBlank() },
                ownerApp = owner?.appKey ?: existing.ownerApp,
                ownerKey = owner?.recordKey ?: existing.ownerKey,
                ownerLabel = owner?.label ?: existing.ownerLabel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Delete a document and the file behind it — the row first.
     *
     * That order is deliberate: a file deleted ahead of a row that then fails to delete leaves a
     * document the app claims to have and cannot open, which is worse than a stray file.
     */
    suspend fun delete(id: String) {
        val existing = dao.getDocument(id) ?: return
        dao.deleteDocument(id)
        files.delete(existing.fileName)
    }

    /**
     * Tell the shelf what a record is called now.
     *
     * The one upkeep cost of carrying a label instead of a foreign key, and the reason it is worth
     * paying: the alternative is this module knowing what an asset, a person and a project are.
     */
    suspend fun relabel(appKey: String, recordKey: String, label: String) =
        dao.relabel(appKey, recordKey, label, System.currentTimeMillis())

    /**
     * Everything an app filed, for the moment one of its records is deleted.
     *
     * The owning app decides what that means — Maintenance takes an asset's documents with it — and
     * says so by calling [delete]. Repository does not cascade on somebody else's rules.
     */
    suspend fun filedBy(appKey: String): List<DocumentFacts> = dao.filedBy(appKey).map { it.toFacts() }

    /** Delete every document filed against one record. Called by the owning app, never guessed at. */
    suspend fun deleteFiledOn(appKey: String, recordKey: String) {
        dao.filedBy(appKey).filter { it.ownerKey == recordKey }.forEach { delete(it.id) }
    }

    // ------------------------------------------------------------------ handing one over

    /**
     * A copy of a document, named after its title, ready to be handed to another app.
     *
     * Works for foreign documents too — that is the whole point of the shelf being one list. The
     * lending app supplies its file; the copy is made here, so a document leaves by exactly one road
     * however it got onto the shelf.
     */
    suspend fun exportCopy(document: DocumentFacts): File? {
        val sourceKey = document.sourceKey
        if (sourceKey == null) {
            val row = dao.getDocument(document.id) ?: return null
            return files.exportCopy(row.fileName, row.title)
        }
        val lent = DocumentSources.of(sourceKey)?.open(document.id) ?: return null
        return files.exportCopyOf(lent, document.title)
    }

    // ------------------------------------------------------------------ backup

    /** Every row, for the backup contributor. */
    suspend fun allRows(): List<DocumentEntity> = dao.allDocuments()

    private fun DocumentEntity.toFacts() = DocumentFacts(
        id = id,
        title = title,
        kind = DocumentKind.fromKey(kind),
        owner = DocumentOwner(ownerApp, ownerKey, ownerLabel),
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        addedAt = addedAt,
        note = note,
        sourceKey = null
    )

    /** A source's documents, stamped with where they came from so nothing else has to remember. */
    private fun DocumentSource.lend(): Flow<List<DocumentFacts>> =
        runCatching {
            observeDocuments().map { facts -> facts.map { it.copy(sourceKey = appKey) } }
        }.getOrElse { flowOf(emptyList()) }
}
