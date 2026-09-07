package com.repository.app.data.repository

import android.net.Uri
import com.repository.app.data.db.dao.RepositoryDao
import com.repository.app.data.db.entities.DocumentEntity
import com.repository.app.data.store.DocumentFiles
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.Documents
import com.repository.app.logic.RepositoryDestination
import com.repository.app.logic.ShelfManifest
import com.repository.app.logic.Shelf
import com.repository.app.logic.Transfer
import com.repository.app.logic.TransferChoice
import com.repository.app.logic.TransferOutcome
import com.repository.app.source.DocumentSource
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

    /**
     * The whole shelf as it stands right now — this app's rows and every lender's, in one list.
     *
     * The one-shot form of [observeShelf], for the callers that are answering a question rather than
     * drawing a screen: a link being checked, a route being served. It is a real read of every
     * source each time it is called, which is right for both — a household's shelf is a few hundred
     * rows, and an answer assembled from a cached list is an answer about a shelf that has changed.
     */
    suspend fun everything(): List<DocumentFacts> = observeShelf().first()

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
        addedAt: Long = System.currentTimeMillis(),
        /**
         * The id to file it under, for a document arriving from another shelf with its own already
         * minted (see `logic/Sidecar`). Fresh for everything else, which is every other caller.
         *
         * Safe because ids are UUIDs: one from another install cannot collide with one of ours, so
         * an id that is already here means the same document coming round again — which [importAll]
         * checks for and skips rather than filing twice.
         */
        id: String = UUID.randomUUID().toString()
    ): String? {
        val picked = files.describe(source)
        val stored = files.save(source, picked) ?: return null
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

    /**
     * File a whole reviewed batch — the four project docs somebody just picked off a drive.
     *
     * One at a time, and each independently: a file that cannot be read takes its own row down and
     * nothing else. That is the difference between a batch import and a transaction, and it is the
     * right one here — three of four documents filed is three documents the household has, whereas
     * an all-or-nothing import throws away three good copies because a Google Doc had no bytes to
     * export.
     *
     * Failures come back **named**, because "one of these couldn't be read" is a sentence that makes
     * somebody re-import all four.
     */
    suspend fun fileAll(
        choices: List<TransferChoice>,
        kind: DocumentKind,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        note: String? = null
    ): TransferOutcome = withContext(Dispatchers.IO) {
        var filed = 0
        val failed = mutableListOf<String>()
        Transfer.included(choices).forEach { choice ->
            val id = runCatching {
                file(
                    source = Uri.parse(choice.item.uri),
                    title = choice.title,
                    kind = kind,
                    owner = owner,
                    note = note
                )
            }.getOrNull()
            if (id == null) {
                failed += choice.title.trim().ifBlank { choice.item.displayName.orEmpty() }
                    .ifBlank { "One file" }
            } else {
                filed++
            }
        }
        TransferOutcome(filed, failed)
    }

    /**
     * File a reviewed batch that arrived off a drive **with the shelf that wrote it**.
     *
     * The plain [fileAll] is what an import without a manifest does: bytes in, a title somebody
     * typed over whatever the file was called, and the household's own drawer. This is the same
     * thing when the folder also holds a `repository-shelf.json` — each file is matched to its entry
     * by name, and arrives with the title, kind, note and *what it is about* that the other shelf
     * had for it.
     *
     * Two rules make re-picking a folder safe, which matters because nobody remembers which four
     * files were the new ones:
     *
     * - **A document already here is skipped, not re-filed.** Matched on the id the exporting shelf
     *   minted, which the manifest carries.
     * - **Nothing existing is ever overwritten.** Not the title, not the note, not the drawer. Two
     *   phones both editing a caption is a conflict this app cannot resolve and has no business
     *   guessing at, and an import that quietly reverted a rename would be worse than one that did
     *   nothing.
     *
     * A file the manifest says nothing about is filed exactly as [fileAll] would file it — an import
     * of four documents and one holiday photo should not refuse the photo.
     */
    suspend fun importAll(
        choices: List<TransferChoice>,
        manifest: ShelfManifest?,
        kind: DocumentKind,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        note: String? = null
    ): TransferOutcome = withContext(Dispatchers.IO) {
        var filed = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        Transfer.included(choices).forEach { choice ->
            val entry = manifest?.entryFor(choice.item.displayName)

            if (entry != null && dao.getDocument(entry.id) != null) {
                skipped++
                return@forEach
            }

            val id = runCatching {
                file(
                    source = Uri.parse(choice.item.uri),
                    // The title from the manifest, not the one the review list guessed from the file
                    // name — unless somebody edited it in the review list, in which case they mean it.
                    title = choice.title,
                    kind = entry?.documentKind ?: kind,
                    owner = entry?.owner ?: owner,
                    note = entry?.note ?: note,
                    addedAt = entry?.addedAt ?: System.currentTimeMillis(),
                    id = entry?.id ?: UUID.randomUUID().toString()
                )
            }.getOrNull()

            if (id == null) {
                failed += choice.title.trim().ifBlank { choice.item.displayName.orEmpty() }
                    .ifBlank { "One file" }
            } else {
                filed++
            }
        }
        TransferOutcome(filed, failed, skipped)
    }

    /**
     * Move a document into another drawer — or back out to the household's.
     *
     * This is the other half of a targeted import: a document grabbed onto the shelf can be attached
     * to a project afterwards, without being copied again. [update] deliberately treats a null owner
     * as "leave it where it is", which is right for a rename dialog and useless here, so re-filing
     * is its own call and sets all three owner fields including to null.
     *
     * A document another app is only lending cannot be re-filed and is not here: `sourceKey` rows
     * never reach this class's writes. See `source/DocumentSource`.
     */
    suspend fun refile(id: String, owner: DocumentOwner) {
        val existing = dao.getDocument(id) ?: return
        dao.upsertDocument(
            existing.copy(
                ownerApp = owner.appKey,
                ownerKey = owner.recordKey,
                ownerLabel = owner.label,
                updatedAt = System.currentTimeMillis()
            )
        )
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

    // ------------------------------------------------------------------ opening at a place

    /**
     * An address checked against what is actually on the shelf, or null if the thing it names has
     * gone.
     *
     * Every way into this app from outside names rows by id, and by the time one is opened the row
     * may not be there: Advisor can ground an answer in a statement thrown away since, and an asset
     * screen can hand over a record whose last document was deleted on another screen a minute ago.
     * So a destination is resolved *before* the shelf is narrowed to it, and a stale one comes back
     * null — the caller opens the shelf plainly, which is the whole list rather than an empty one.
     *
     * Resolution is against the **merged** shelf, sources included, because a lent document is on
     * the shelf exactly as much as a filed one and a link to a lab result must not depend on which
     * app happens to be holding it.
     */
    suspend fun resolve(destination: RepositoryDestination): RepositoryDestination? {
        if (destination is RepositoryDestination.Shelf) return destination
        val shelf = everything()
        return when (destination) {
            // Handled above; repeated so this stays an exhaustive `when` over the destinations
            // rather than a `when` with an else that would swallow the next one somebody adds.
            is RepositoryDestination.Shelf -> destination

            is RepositoryDestination.Drawer ->
                destination.takeIf { shelf.any { doc -> doc.owner.appKey == destination.appKey } }

            is RepositoryDestination.Record -> destination.takeIf {
                shelf.any { doc ->
                    doc.owner.appKey == destination.appKey && doc.owner.recordKey == destination.recordKey
                }
            }

            is RepositoryDestination.Document -> destination.takeIf {
                shelf.any { doc ->
                    doc.id == destination.documentId && doc.sourceKey == destination.sourceKey
                }
            }
        }
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

    /**
     * The stored file behind one of this app's own documents, in place.
     *
     * The one hand-over that does **not** copy, and it is safe not to precisely because the caller
     * cannot write: `provider/RepositoryDocumentsProvider` opens it read-only for the system file
     * browser. Every other road out goes through [exportCopy] into `cacheDir/exports`, because those
     * hand the file to another app that may do anything with it.
     */
    suspend fun fileOf(id: String): File? {
        val row = dao.getDocument(id) ?: return null
        return files.file(row.fileName)
    }

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
