package com.repository.app.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.operations.backupkit.AppId
import com.repository.app.R
import com.repository.app.RepositoryApp
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.Documents
import com.repository.app.logic.RepositoryDestination
import com.repository.app.logic.RepositoryLinks
import com.repository.app.logic.Shelf
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * The shelf, in the system Files app and in every other app's Open dialog.
 *
 * This is the last door. Repository already had two — its own screen, and the section it lends to
 * the app that owns a thing — and both are inside the suite. A household attaching the mortgage
 * statement to an email, or picking the warranty in a form on a council website, was still going
 * through Downloads and a folder of files named `Scan_20240412.pdf`. From here the shelf is one of
 * the places Android offers, beside Drive and Downloads, with the same drawers and the same names as
 * the app.
 *
 * ### It is read-only, and that is the same line the routes sit on
 *
 * No `FLAG_SUPPORTS_CREATE`, `DELETE`, `WRITE`, `RENAME` or `MOVE` anywhere below, and none of
 * `createDocument`, `deleteDocument` or `renameDocument` is implemented. A picker that could delete
 * would let any app on the phone destroy a document through a dialog the household did not read
 * carefully, and the bytes may be the only copy in the house. Filing, deleting and re-filing happen
 * where somebody can see the whole shelf while they do it. See `connection/RepositoryConnections`
 * for the same three refusals in the other doorway.
 *
 * ### The ids are the app's own addresses
 *
 * A `DocumentsProvider` needs a stable string per document, and Repository already had one: the deep
 * link vocabulary in `logic/RepositoryLinks` (`shelf`, `drawer/maintenance`, `document/<id>`,
 * `lent/health/<id>`). Reusing it means there is *one* way to write down "somewhere on the shelf" —
 * the id the Files app remembers in a recent-documents list is the same string that opens the shelf
 * at that document — rather than a second vocabulary that could drift from the first.
 *
 * ### Blocking, on purpose
 *
 * Every method here is called by the system on a binder thread and must answer before it returns, so
 * the store's suspending reads are wrapped in `runBlocking`. That is the contract of the interface
 * rather than a shortcut: the alternative is a cursor handed back empty and filled in later, which
 * is what `setNotificationUri` is for and what a shelf of a few hundred rows does not need.
 */
class RepositoryDocumentsProvider : DocumentsProvider() {

    private val shelf get() = RepositoryApp.get(context!!)

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------------ the root

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, RepositoryLinks.format(RepositoryDestination.Shelf))
            add(Root.COLUMN_TITLE, context!!.getString(R.string.repository_root_title))
            add(Root.COLUMN_SUMMARY, context!!.getString(R.string.repository_root_summary))
            add(Root.COLUMN_ICON, R.drawable.ic_repository_shelf)
            // Searchable, because search is the whole app: the shelf has no folders to have filed
            // things correctly in, and the search here is the same one the screen runs — over the
            // title, the note, the kind and what the document is about.
            //
            // Deliberately not FLAG_SUPPORTS_CREATE: nothing files a document through a picker.
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    // ------------------------------------------------------------------ one document

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        when (val destination = RepositoryLinks.parse(documentId)) {
            is RepositoryDestination.Shelf ->
                cursor.addDirectory(documentId!!, context!!.getString(R.string.repository_root_title))

            is RepositoryDestination.Drawer ->
                cursor.addDirectory(documentId!!, drawerLabel(destination.appKey))

            is RepositoryDestination.Document ->
                cursor.addDocument(find(destination) ?: throw FileNotFoundException(documentId))

            // A record is a real address in the app and not a place in a file browser: the drawers
            // here are per app, and a folder per asset would be the folders this app refuses to
            // have. Somebody browsing sees a drawer and searches inside it.
            else -> throw FileNotFoundException(documentId)
        }
        return cursor
    }

    // ------------------------------------------------------------------ what is inside

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val everything = runBlocking { shelf.documents.everything() }

        when (val destination = RepositoryLinks.parse(parentDocumentId)) {
            // The root is the drawers, in the shelf's own order: the household's own first, because
            // a document belonging to no app is the one nothing else will ever show you.
            is RepositoryDestination.Shelf ->
                Shelf.drawers(everything) { key -> AppId.fromKey(key)?.defaultDisplayName }
                    .forEach { group ->
                        val id = RepositoryLinks.format(RepositoryDestination.Drawer(group.appKey))
                            ?: return@forEach
                        cursor.addDirectory(id, group.label)
                    }

            is RepositoryDestination.Drawer ->
                everything.filter { it.owner.appKey == destination.appKey }
                    .let { Shelf.order(it) }
                    .forEach { cursor.addDocument(it) }

            else -> throw FileNotFoundException(parentDocumentId)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = RepositoryLinks.parse(parentDocumentId)
        val child = RepositoryLinks.parse(documentId)
        return when {
            parent is RepositoryDestination.Shelf -> child != null && child !is RepositoryDestination.Shelf
            parent !is RepositoryDestination.Drawer -> false
            child !is RepositoryDestination.Document -> false
            else -> find(child)?.owner?.appKey == parent.appKey
        }
    }

    // ------------------------------------------------------------------ searching

    /**
     * Search, as the document UI asks for it.
     *
     * This is the deprecated three-argument form and it is still the right one to override: from
     * API 34 the platform routes a search to the `Bundle` overload, whose own implementation pulls
     * `QUERY_ARG_DISPLAY_NAME` out of the arguments and calls straight back into this. Overriding
     * both would mean two copies of the same search with one of them unreachable.
     */
    @Suppress("DEPRECATION")
    override fun querySearchDocuments(
        rootId: String?,
        query: String?,
        projection: Array<out String>?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (rootId != ROOT_ID) return cursor
        val everything = runBlocking { shelf.documents.everything() }
        // The same search the screen runs, so "wrangler" finds the truck's manual from the Files app
        // as well — and this module still has no idea what a Wrangler is.
        Shelf.search(everything, query.orEmpty()).forEach { cursor.addDocument(it) }
        return cursor
    }

    // ------------------------------------------------------------------ handing one over

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        // Reading only. A write mode is refused rather than quietly downgraded, because an app told
        // it had a writable descriptor will act as though its edits were kept.
        if (mode != null && mode != "r") {
            throw UnsupportedOperationException("The shelf hands documents out to be read, not written")
        }
        val destination = RepositoryLinks.parse(documentId) as? RepositoryDestination.Document
            ?: throw FileNotFoundException(documentId)

        val file = runBlocking { fileFor(destination) } ?: throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * The bytes behind a document, wherever they live.
     *
     * This app's own are handed over where they lie, read-only — the descriptor cannot write, so
     * there is nothing to protect them from and no copy worth making. A **lent** document's file
     * belongs to the app lending it, and comes through that app's own `DocumentSource`, so Health
     * still decides what it is willing to hand over.
     */
    private suspend fun fileFor(destination: RepositoryDestination.Document): File? {
        val lender = destination.sourceKey
        if (lender != null) return DocumentSources.of(lender)?.open(destination.documentId)
        return shelf.documents.fileOf(destination.documentId)
    }

    // ------------------------------------------------------------------ rows

    private fun MatrixCursor.addDirectory(documentId: String, name: String) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            // No CREATE, no DELETE: a drawer is a view of the shelf, not a folder to put things in.
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, null)
        }
    }

    private fun MatrixCursor.addDocument(document: DocumentFacts) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, document.address())
            // Named the way it leaves by every other road too — its title, made safe, plus the
            // extension its type implies. One rule, so a statement arrives under the same name in an
            // email, in OneDrive and here.
            add(Document.COLUMN_DISPLAY_NAME, document.fileName())
            add(Document.COLUMN_MIME_TYPE, document.mimeType ?: "application/octet-stream")
            add(Document.COLUMN_SIZE, document.sizeBytes)
            // When it was filed. Not the date on the document — nothing here reads the document.
            add(Document.COLUMN_LAST_MODIFIED, document.addedAt)
            add(Document.COLUMN_FLAGS, 0)
            // What it is about, where a file browser has room to say so — "2018 Jeep Wrangler",
            // which is the one thing a folder of scans could never tell anybody.
            add(Document.COLUMN_SUMMARY, document.summaryLine())
        }
    }

    private fun drawerLabel(appKey: String?): String =
        appKey?.let { AppId.fromKey(it)?.defaultDisplayName ?: it } ?: Shelf.HOUSEHOLD_LABEL

    private fun find(destination: RepositoryDestination.Document): DocumentFacts? =
        runBlocking { shelf.documents.everything() }.firstOrNull {
            it.id == destination.documentId && it.sourceKey == destination.sourceKey
        }

    companion object {

        /** One root: the shelf. There is nothing else this app could offer a file browser. */
        const val ROOT_ID = "shelf"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY
        )
    }
}

/**
 * A document's address — the same string that opens the shelf at it.
 *
 * Never null in practice: this app's ids are UUIDs and a lender's key is an `AppId`. A document
 * whose id somehow could not be written down is simply not offered to the file browser, which is the
 * right failure — an id that means something different from what was asked for would hand somebody
 * the wrong document.
 */
internal fun DocumentFacts.address(): String? =
    RepositoryLinks.format(RepositoryDestination.Document(id, sourceKey))

/** The name a document is shown and saved under, by the one rule every road out uses. */
internal fun DocumentFacts.fileName(): String =
    Documents.exportFileName(title, Documents.extensionFor(null, mimeType))

/**
 * The line under a document in a file browser: what it is about, and what kind it is.
 *
 * The thing a folder of scans can never say. "2018 Jeep Wrangler · Manual or instructions" beats
 * `Scan_20240412.pdf` by the whole distance this app exists to cover.
 */
internal fun DocumentFacts.summaryLine(): String =
    listOfNotNull(owner.label, kind.label).joinToString(" · ")
