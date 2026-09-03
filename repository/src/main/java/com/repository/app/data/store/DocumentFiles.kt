package com.repository.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.repository.app.logic.Documents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where the household's paperwork lives — `filesDir/documents/`.
 *
 * The shape is Health's, which proved it: the bytes sit beside the database rather than inside it,
 * the row stores only a **file name**, the directory is carried by the backup, and it is restored
 * *before* the rows that name it. A database that a backup copies whole should not have a folder of
 * PDFs in it.
 *
 * `filesDir`, not `cacheDir`: a mortgage statement is a record the household chose to keep, and the
 * system may reclaim a cache at any time.
 *
 * ### Pictures are re-encoded. Everything else is copied byte for byte.
 *
 * The distinction matters and is not an optimisation.
 *
 * A **photograph** of a document is a photograph: twelve megapixels of a sheet of A4, most of it
 * noise. Downsampling costs nothing a reader can see and keeps the shelf from growing by eight
 * megabytes a page.
 *
 * A **PDF** is the document itself — very often the lender's own file, the one with the letterhead
 * and the signature. Re-encoding it would produce a different file from the one the household was
 * given, and "different from what the bank sent" is exactly the property a record must not have. So
 * a PDF, and anything else that is not an image, is copied verbatim and handed back verbatim.
 */
class DocumentFiles(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** What the picker said about a file, before anything has been copied. */
    data class Picked(val displayName: String?, val mimeType: String?, val sizeBytes: Long?)

    /** What was actually stored, for the row that is about to be written. */
    data class Stored(val fileName: String, val mimeType: String?, val sizeBytes: Long)

    /**
     * Ask the content resolver what it knows about a picked file.
     *
     * Both columns are genuinely optional — plenty of providers supply neither — so every field is
     * nullable and the form treats them as suggestions rather than facts.
     */
    fun describe(source: Uri): Picked {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(source, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        }
        return Picked(
            displayName = name,
            mimeType = runCatching { context.contentResolver.getType(source) }.getOrNull(),
            sizeBytes = size
        )
    }

    /**
     * Copy a picked file into the store and return what to save on the row.
     *
     * Returns null rather than throwing when the file cannot be read — a corrupt file, a URI whose
     * permission has already lapsed, a provider that has gone away. A document that fails to attach
     * should leave the screen standing.
     *
     * A partially written file is deleted rather than left behind: a half-copied PDF that a row
     * points at is worse than no attachment, because the app looks like it has the document.
     */
    suspend fun save(source: Uri, picked: Picked = describe(source)): Stored? = withContext(Dispatchers.IO) {
        val asImage = Documents.isImage(picked.mimeType)
        val extension = if (asImage) "jpg" else Documents.extensionFor(picked.displayName, picked.mimeType)
        val name = "${UUID.randomUUID()}.$extension"
        val target = File(dir, name)

        runCatching {
            if (asImage) {
                val bitmap = decodeDownsampled(source) ?: return@runCatching null
                target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
                bitmap.recycle()
            } else {
                val input = context.contentResolver.openInputStream(source) ?: return@runCatching null
                input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            }
            Stored(
                fileName = name,
                // An image is now a JPEG whatever it arrived as, so the row must not keep claiming
                // it is a HEIC — the type on the row is what a share sheet announces.
                mimeType = if (asImage) "image/jpeg" else picked.mimeType,
                sizeBytes = target.length()
            )
        }.getOrElse {
            runCatching { target.delete() }
            null
        }
    }

    /** The stored file for a name, or null if the row points at something no longer there. */
    fun file(fileName: String?): File? {
        // A row should only ever hold a bare file name; anything that tries to climb out of the
        // directory is refused rather than resolved.
        if (!Documents.isSafeFileName(fileName)) return null
        return File(dir, fileName!!.trim()).takeIf { it.exists() }
    }

    /**
     * Delete a file nothing points at any more.
     *
     * Called after the row that referenced it is gone, never before: a file deleted ahead of a write
     * that then fails leaves a document pointing at nothing. Failures are swallowed — an orphaned
     * file wastes a little space and there is nothing useful to say about it mid-edit.
     */
    fun delete(fileName: String?) {
        runCatching { file(fileName)?.delete() }
    }

    /**
     * Copy a stored document into `cacheDir/exports` so it can be handed to another app.
     *
     * `RepositoryFileProvider` exposes **only** that directory. The documents themselves are the
     * household's records, not something every app on the device is invited to read, and widening
     * the provider to the store would make every statement in the house readable by anything that
     * could guess a URI. Copying on request means a document leaves only when somebody asks it to.
     *
     * The copy is named after the document's [title], so what arrives in an email is
     * "Mortgage statement March 2026.pdf" rather than a UUID.
     */
    suspend fun exportCopy(fileName: String?, title: String): File? = withContext(Dispatchers.IO) {
        exportCopyOf(file(fileName) ?: return@withContext null, title)
    }

    /**
     * The same hand-over for a file this app does not own — one an app is lending the shelf.
     *
     * A document leaves by exactly one road however it got onto the shelf, which is what makes the
     * one rule above ("only cacheDir/exports is exposed") true of all of them rather than most.
     */
    suspend fun exportCopyOf(stored: File, title: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val exports = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val target = File(exports, Documents.exportFileName(title, stored.extension))
            stored.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    /** Every stored document, for the backup contributor to carry. */
    fun allFiles(): List<File> = dir.listFiles { f -> f.isFile }?.toList().orEmpty()

    /** Where the backup puts the files back, before the rows that name them are restored. */
    fun fileFor(name: String): File? =
        if (Documents.isSafeFileName(name)) File(dir, name) else null

    /**
     * Decode a picked image no larger than it needs to be.
     *
     * Two passes: the first reads only the bounds, so a twelve-megapixel photo never lands in memory
     * whole, and the second decodes at the nearest power-of-two subsample. That is the same approach
     * Health's downsampler takes, kept short here because Repository needs the one path.
     */
    private fun decodeDownsampled(source: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var width = bounds.outWidth
        var height = bounds.outHeight
        while (width / 2 >= MAX_EDGE_PX && height / 2 >= MAX_EDGE_PX) {
            width /= 2
            height /= 2
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    companion object {
        /** Carried by the backup alongside `repository.db`; see `RepositoryBackupContributor`. */
        const val DIR_NAME = "documents"

        /** Matches the `<cache-path>` in `res/xml/repository_file_paths.xml`. */
        private const val EXPORT_DIR = "exports"

        /** Enough to read a page of A4 on a phone and to zoom into it; far short of a camera's raw. */
        private const val MAX_EDGE_PX = 2_400

        private const val JPEG_QUALITY = 85
    }
}
