package com.health.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where photographs of insurance cards are kept.
 *
 * ### Why files, and why here
 *
 * A card photo is a couple of megabytes. Putting it in the database would mean `health.db` — which is
 * WAL-checkpointed and copied whole by the backup contributor every time anybody records a
 * temperature — carrying eight megabytes of JPEG for a household of four. So the bytes go in
 * `filesDir/insurance-cards/`, the row stores only the **file name**, and the backup carries the
 * directory alongside the database (see `backup/HealthBackupContributor`).
 *
 * `filesDir`, not `cacheDir`: a card photo is a record the user chose to keep, and the system is
 * free to reclaim a cache at any time. It is the same distinction Citation draws between its
 * sovereign and disposable stores, and a card that quietly disappears the week the phone runs low on
 * space is exactly the failure this feature exists to prevent.
 *
 * ### What happens to the picture
 *
 * It is decoded, downsampled to something a card-sized page can use, and re-encoded as JPEG. A modern
 * phone camera produces a twelve-megapixel image of a piece of plastic the size of a credit card;
 * keeping that verbatim would make the store ten times larger and the PDF slower to open, for detail
 * no reader can use. [MAX_EDGE_PX] is generous enough that a member number stays legible when the
 * page is zoomed, which is the only resolution requirement this feature has.
 *
 * Nothing here reaches the network, and nothing here is shared: the directory is inside Health's own
 * private storage, and the only way a card leaves it is the export the user explicitly asks for.
 */
class CardImageStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * Copy a picked image into the store, downsampled, and return the file name to save on the row.
     *
     * Returns null rather than throwing when the picture can't be read — a corrupt file, a URI whose
     * permission has already lapsed, a "photo" that is really a PDF. A card that fails to attach
     * should leave the form standing, not take the screen down.
     */
    suspend fun save(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDownsampler.decode(context, source, MAX_EDGE_PX) ?: return@runCatching null
            val name = "${UUID.randomUUID()}.jpg"
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            name
        }.getOrNull()
    }

    /** The stored file for a name, or null if the row points at something that is no longer there. */
    fun file(fileName: String?): File? {
        val name = fileName?.trim()?.ifBlank { null } ?: return null
        // Defensive: a row should only ever hold a bare file name, and a path that tries to climb out
        // of the directory is not one — refuse rather than resolve it.
        if (name.contains('/') || name.contains("..")) return null
        return File(dir, name).takeIf { it.exists() }
    }

    /** Read a stored card back for the screen or the PDF. Null when the file has gone. */
    fun load(fileName: String?): Bitmap? {
        val file = file(fileName) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /**
     * Delete a file nothing points at any more.
     *
     * Called by the repository *after* the row that referenced it is written, never before: a file
     * deleted ahead of a write that then fails leaves a card pointing at nothing. Failures are
     * swallowed — an orphaned file wastes a little space, and there is nothing useful to tell the
     * user about it mid-edit.
     */
    fun delete(fileName: String?) {
        runCatching { file(fileName)?.delete() }
    }

    /** Every stored card image, for the backup contributor to carry. */
    fun allFiles(): List<File> =
        dir.listFiles { f -> f.isFile }?.toList().orEmpty()

    companion object {
        /** Carried by the backup alongside `health.db`; see `HealthBackupContributor`. */
        const val DIR_NAME = "insurance-cards"

        /**
         * Long edge, in pixels — enough to keep a member number sharp when somebody zooms the
         * exported PDF, which is the only resolution this feature actually needs. The decode itself
         * lives in [ImageDownsampler], shared with the document store.
         */
        private const val MAX_EDGE_PX = ImageDownsampler.DEFAULT_MAX_EDGE_PX

        private const val JPEG_QUALITY = ImageDownsampler.DEFAULT_JPEG_QUALITY
    }
}
