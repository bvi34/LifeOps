package com.logistics.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Where the screenshots kept with recipes live.
 *
 * ### Why files, and why here
 *
 * A screenshot is a megabyte or two. Putting it in the database would mean `logistics.db` — which
 * the sandbox backup copies whole every time anybody shelves a grocery run — carrying twenty
 * megabytes of JPEG for a handful of recipes. So the bytes go in `filesDir/recipe-shots/`, the row
 * keeps only the **file name**, and the backup carries the directory alongside the database (see
 * `backup/LogisticsBackupContributor`). It is the same split Health draws for insurance cards.
 *
 * `filesDir`, not `cacheDir`: a recipe screenshot is a record the user chose to keep — often the
 * *only* copy of a recipe that never existed as a web page — and the system may reclaim a cache at
 * any time.
 *
 * ### What happens to the picture
 *
 * It is decoded, downsampled, and re-encoded as JPEG. A screenshot is already screen-resolution, but
 * a *photograph* of a cookbook page off a modern camera is twelve megapixels of paper;
 * [MAX_EDGE_PX] keeps small type legible when the picture is opened full-screen, which is the only
 * resolution requirement this feature has.
 *
 * Nothing here touches the network, and the directory is inside the app's own private storage.
 */
class RecipeShotStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * Copy a picked image into the store, downsampled, and return the file name to save on the row.
     * Null rather than an exception when the picture can't be read — a corrupt file, a lapsed URI
     * permission, a "photo" that is really a PDF. A screenshot that fails to attach should leave the
     * import standing, not take the screen down.
     */
    suspend fun save(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(source) ?: return@runCatching null
            val name = "${UUID.randomUUID()}.jpg"
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            name
        }.getOrNull()
    }

    /** The stored file for a name, or null when the row points at something no longer there. */
    fun file(fileName: String?): File? {
        val name = fileName?.trim()?.ifBlank { null } ?: return null
        // Defensive: a row should only ever hold a bare file name, and a path that tries to climb
        // out of the directory is not one — refuse rather than resolve it.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(dir, name).takeIf { it.exists() }
    }

    /** Read a stored screenshot back for the screen. Null when the file has gone. */
    fun load(fileName: String?): Bitmap? {
        val file = file(fileName) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** Decode a picked image without ever holding its full-resolution bitmap. Also what the OCR
     *  reads from, so the text it recognises is the picture that gets stored. */
    fun decode(source: Uri, maxEdgePx: Int = MAX_EDGE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxEdgePx) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    /**
     * Delete a file nothing points at any more. Called *after* the row that referenced it is gone,
     * never before: a file deleted ahead of a write that then fails leaves a recipe pointing at
     * nothing. Failures are swallowed — an orphaned file wastes a little space and there is nothing
     * useful to say about it mid-edit.
     */
    fun delete(fileName: String?) {
        runCatching { file(fileName)?.delete() }
    }

    /** Every stored screenshot, for the backup contributor to carry. */
    fun allFiles(): List<File> = dir.listFiles { f -> f.isFile }?.toList().orEmpty()

    companion object {
        /** Carried by the backup alongside `logistics.db`; see `LogisticsBackupContributor`. */
        const val DIR_NAME = "recipe-shots"

        /**
         * Long edge in pixels. A phone screenshot is around this already; a photograph of a page is
         * far larger, and the difference is a store ten times the size for detail no reader can use.
         * Generous enough that the small print of an ingredient list stays sharp when zoomed, which
         * is what somebody checks a parse against.
         */
        private const val MAX_EDGE_PX = 1600

        private const val JPEG_QUALITY = 85
    }
}
