package com.repository.app.data.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.repository.app.logic.Documents
import com.repository.app.logic.Drive
import com.repository.app.logic.TransferItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The road to and from a drive, in the only terms Android offers: a document provider.
 *
 * Google Drive, OneDrive and Dropbox each publish one. So "grab these four files off OneDrive" is
 * the system picker opened at OneDrive with multi-select on, and "put this statement in that folder"
 * is a folder the household granted once and a `createDocument` into it. There is no client, no
 * token, and nothing this class can see that somebody did not point at.
 *
 * Two grants are taken and they are different things:
 *
 * - a **file** grant, taken on each picked URI, so the copy can outlive the activity that picked it
 *   — a fifteen-megabyte PDF over a phone connection routinely does;
 * - a **folder** grant, taken on a tree the household chose to export into, so the next document can
 *   go to the same place without asking again. That grant is the one thing here worth being careful
 *   about, which is why the folder is chosen rather than guessed and is remembered per drive rather
 *   than globally: "the folder I put project docs in on OneDrive" is a different answer from "the
 *   folder I put statements in on Drive".
 */
class DriveTransfer(private val context: Context, private val files: DocumentFiles) {

    /**
     * What the picker just handed back, described as far as the providers will describe it.
     *
     * Suspending and off the main thread, unlike the single-file case it wraps: describing a file
     * means a query into the provider that owns it, and a cloud provider's answer can involve the
     * network. One of those on the main thread is a stutter; seven in a row, which is exactly what a
     * targeted import is, is a frozen dialog.
     */
    suspend fun describe(uris: List<Uri>): List<TransferItem> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val picked = files.describe(uri)
            TransferItem(
                uri = uri.toString(),
                displayName = picked.displayName,
                mimeType = picked.mimeType,
                sizeBytes = picked.sizeBytes
            )
        }
    }

    /**
     * Hold on to a picked file for longer than this screen.
     *
     * Best-effort on purpose: plenty of providers hand back a URI they will not make persistable,
     * and the copy that follows immediately works anyway. Failing the whole import over a refused
     * grant would be losing the file to protect a convenience.
     */
    fun holdOnTo(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Keep the folder the household chose to export into, so the next document does not ask again.
     *
     * Read *and* write, because a folder you can only read is not a folder you can save into.
     * Returns whether the grant stuck: a folder the system will not let us keep is one the app must
     * ask for again next time rather than remember and then fail at.
     */
    fun keepFolder(tree: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    }.getOrElse { false }

    /** Whether a remembered folder is still ours to write into — a grant can be revoked. */
    fun canStillWriteTo(tree: Uri?): Boolean {
        if (tree == null) return false
        return context.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && it.uri == tree
        }
    }

    /**
     * Write a stored document into a folder on a drive, named after its title.
     *
     * The name is [Documents.exportFileName] — the same one a share sheet gets — so a document has
     * one name wherever it lands. If the folder already holds a file with that name the provider
     * decides what happens, and every one of them answers by making "Statement (1).pdf" rather than
     * overwriting. That is the right default and it is not ours to override: this app is a copy of
     * the household's records, and silently replacing a file on their drive is the one outcome an
     * export must never have.
     *
     * Returns the URI of what was written, or null if the provider refused — the caller counts the
     * nulls and says how many did not make it.
     */
    suspend fun saveInto(
        folder: Uri,
        stored: File,
        title: String,
        mimeType: String?
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                folder,
                DocumentsContract.getTreeDocumentId(folder)
            )
            val name = Documents.exportFileName(title, stored.extension)
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mimeType ?: DEFAULT_MIME,
                name
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(created)?.use { out ->
                stored.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null
            created
        }.getOrNull()
    }

    /** What to call a chosen folder on screen — the provider's own display name for it. */
    fun folderLabel(tree: Uri?): String? {
        if (tree == null) return null
        return runCatching {
            val document = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            context.contentResolver.query(document, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getString(it) }
            }
        }.getOrNull()
    }

    /**
     * Where to open the picker, for a drive somebody has picked from before.
     *
     * `EXTRA_INITIAL_URI` is a *hint* and every provider is free to ignore it, which is why the drive
     * chips are described on screen as a starting point rather than a filter. The first time a drive
     * is used there is nothing to hint with and the picker opens wherever it opens; from then on it
     * opens where that drive was last used, which is almost always the folder the household keeps
     * the sort of document they are about to file again.
     */
    fun lastPlaceOn(drive: Drive?, remembered: String?): Uri? {
        if (drive == null || remembered.isNullOrBlank()) return null
        return runCatching { Uri.parse(remembered) }.getOrNull()
    }

    private companion object {
        /**
         * What to create a document as when the row does not know its type.
         *
         * Not a wildcard: providers reject one, and a file created under it has no type on the
         * drive either. Octet-stream is honest — the bytes are the bytes — and the extension on the name
         * is what a desktop will actually open it by.
         */
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
