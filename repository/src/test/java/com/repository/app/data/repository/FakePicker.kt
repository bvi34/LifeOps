package com.repository.app.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * A document picker, as far as the store can tell.
 *
 * The one thing `DocumentRepository.file` is handed from outside is a `Uri` somebody picked, and
 * everything it then does — what the document is called, what extension it is stored under, how big
 * the row says it is, whether a row is written at all — comes out of asking a content provider about
 * that URI. A test that stubbed `DocumentFiles` would be asserting its own stub, so this is a real
 * `ContentProvider`, registered with the real `ContentResolver`, answering the two questions a
 * picker answers: what is this file called, and here are its bytes.
 *
 * It can also refuse. [offerUnreadable] hands back a URI the provider will describe happily and then
 * fail to open, which is not a contrived case: it is a Google Doc with no exportable bytes, a
 * permission that lapsed while a large file was copying, a provider that went away. The store's
 * promise is that such a file leaves *nothing* behind, and that is only worth asserting against
 * something that actually fails at the moment of reading rather than at the moment of picking.
 */
class FakePicker : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val offered = offered[uri.lastPathSegment] ?: return null
        // Both columns are optional in the real thing, so an offer that names neither must produce a
        // cursor with neither rather than a cursor of nulls — that is the shape `describe` reads.
        val columns = buildList {
            if (offered.displayName != null) add(OpenableColumns.DISPLAY_NAME)
            if (offered.claimedSize != null) add(OpenableColumns.SIZE)
        }
        val cursor = MatrixCursor(columns.toTypedArray())
        if (columns.isNotEmpty()) {
            cursor.addRow(listOfNotNull(offered.displayName, offered.claimedSize).toTypedArray())
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = offered[uri.lastPathSegment]?.mimeType

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val offered = offered[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString())
        val bytes = offered.bytes ?: throw FileNotFoundException("no bytes: $uri")
        val staged = File(stagingDir, offered.id).apply { writeBytes(bytes) }
        return ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private val stagingDir: File
        get() = File(context!!.cacheDir, "picked").apply { mkdirs() }

    private data class Offer(
        val id: String,
        val displayName: String?,
        val mimeType: String?,
        /** What the picker *says* the size is, which a cloud provider often gets wrong or omits. */
        val claimedSize: Long?,
        /** Null for a file that describes itself and then cannot be read. */
        val bytes: ByteArray?
    )

    companion object {

        private const val AUTHORITY = "com.repository.test.picker"

        private val offered = mutableMapOf<String, Offer>()
        private var next = 0

        /** Register the provider and forget anything an earlier test offered. */
        fun install() {
            offered.clear()
            next = 0
            Robolectric.setupContentProvider(FakePicker::class.java, AUTHORITY)
        }

        /** A file the picker will describe and hand over. */
        fun offer(
            displayName: String?,
            bytes: ByteArray = "a document".toByteArray(),
            mimeType: String? = "application/pdf",
            claimedSize: Long? = bytes.size.toLong()
        ): Uri = record(displayName, mimeType, claimedSize, bytes)

        /** A file the picker will describe and then fail to open. */
        fun offerUnreadable(
            displayName: String?,
            mimeType: String? = "application/pdf"
        ): Uri = record(displayName, mimeType, claimedSize = null, bytes = null)

        private fun record(
            displayName: String?,
            mimeType: String?,
            claimedSize: Long?,
            bytes: ByteArray?
        ): Uri {
            val id = "picked-${next++}"
            offered[id] = Offer(id, displayName, mimeType, claimedSize, bytes)
            return Uri.parse("content://$AUTHORITY/$id")
        }
    }
}

/** The documents directory the store writes into — `filesDir/documents`, as the backup carries it. */
internal fun Context.documentsDir(): File = File(filesDir, "documents")

/** Every file actually on disk, so a test can say what a failed copy left behind. */
internal fun Context.storedFiles(): List<String> =
    documentsDir().listFiles()?.map { it.name }?.sorted().orEmpty()
