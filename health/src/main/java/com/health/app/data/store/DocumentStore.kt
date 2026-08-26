package com.health.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.health.app.logic.Documents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where the household's paperwork lives — `filesDir/documents/`.
 *
 * The same shape [CardImageStore] proved and for the same reasons: the bytes sit beside the database
 * rather than inside it, the row stores only a **file name**, the directory is carried by the backup,
 * and it is restored *before* the rows that name it. `health.db` is WAL-checkpointed and copied whole
 * every time anybody records a temperature; a folder of PDFs inside it would be copied along with
 * every one of those.
 *
 * `filesDir`, not `cacheDir`: an after-visit summary is a record the user chose to keep, and the
 * system may reclaim a cache at any time.
 *
 * ### Pictures are re-encoded. Everything else is copied byte for byte.
 *
 * The distinction matters and is not an optimisation.
 *
 * A **photograph** of a document is a photograph: twelve megapixels of a sheet of A4, most of it
 * noise. Downsampling it costs nothing a reader can see and keeps the store from growing by eight
 * megabytes per page. So an image is decoded through [ImageDownsampler] and written as JPEG.
 *
 * A **PDF** is the document itself — very often the practice's own file, the one with the letterhead
 * and the signature. Re-encoding it would produce a different file from the one the household was
 * given, and "different from what the practice sent" is exactly the property a record must not have.
 * So a PDF, and anything else that isn't an image, is copied verbatim and handed back verbatim.
 *
 * ### Nothing here reads what it stores
 *
 * No OCR, no text extraction, no inspection of contents beyond what the operating system already
 * said the file is. Health takes a stream of bytes and gives the same bytes back. See
 * `logic/Documents` for why that line is where it is.
 */
class DocumentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** What the picker said about a file, before anything has been copied. */
    data class Picked(val displayName: String?, val mimeType: String?, val sizeBytes: Long?)

    /** What was actually stored, for the row that is about to be written. */
    data class Stored(val fileName: String, val mimeType: String?, val sizeBytes: Long)

    /**
     * Ask the content resolver what it knows about a picked file.
     *
     * Both columns are genuinely optional — plenty of providers supply neither — so every field here
     * is nullable and the form treats them as suggestions rather than facts. `logic/Documents` has
     * the rules for what to do when they are missing.
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
     * Returns null rather than throwing when the file can't be read — a corrupt file, a URI whose
     * permission has already lapsed, a provider that has gone away. A document that fails to attach
     * should leave the form standing, not take the screen down; that is [CardImageStore]'s rule too.
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
                val bitmap = ImageDownsampler.decode(context, source) ?: return@runCatching null
                target.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, ImageDownsampler.DEFAULT_JPEG_QUALITY, out)
                }
                bitmap.recycle()
            } else {
                val input = context.contentResolver.openInputStream(source) ?: return@runCatching null
                input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
            }
            Stored(
                fileName = name,
                // An image is now a JPEG whatever it arrived as, so the row must not keep claiming
                // it is a HEIC — the type on the row is what a share sheet will announce.
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
     * The indirection is deliberate and matches how the insurance card PDF is shared.
     * `HealthFileProvider` exposes **only** the export directory: the files in `filesDir/documents`
     * are a record, not something every app on the device is invited to read, and widening the
     * provider to the whole store would make every lab result in the house readable by anything that
     * could guess a URI. Copying on request means a document leaves only when somebody asks it to.
     *
     * The name given to the copy is the document's own [title], so what arrives in an email is
     * "Bloods March 2026.pdf" rather than a UUID. Returns null when the stored file has gone.
     */
    suspend fun exportCopy(fileName: String?, title: String): File? = withContext(Dispatchers.IO) {
        val stored = file(fileName) ?: return@withContext null
        runCatching {
            val exports = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val extension = stored.extension.ifBlank { "bin" }
            val safeTitle = title.map { if (it.isLetterOrDigit() || it == ' ') it else '-' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "document" }
            val target = File(exports, "$safeTitle.$extension")
            stored.copyTo(target, overwrite = true)
            target
        }.getOrNull()
    }

    /** Every stored document, for the backup contributor to carry. */
    fun allFiles(): List<File> = dir.listFiles { f -> f.isFile }?.toList().orEmpty()

    companion object {
        /** Carried by the backup alongside `health.db`; see `HealthBackupContributor`. */
        const val DIR_NAME = "documents"

        /** Matches the `<cache-path>` in `res/xml/health_file_paths.xml`. */
        private const val EXPORT_DIR = "exports"
    }
}
