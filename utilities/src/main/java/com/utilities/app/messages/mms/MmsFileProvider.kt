package com.utilities.app.messages.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * A file, handed to the part of the operating system that talks to the carrier.
 *
 * ## Why this has to exist
 *
 * `SmsManager` does the network half of MMS — it knows the carrier's MMSC address, the APN to reach
 * it on, and how to bring up a data connection for it while the phone is on Wi-Fi. What it will not
 * do is hand the bytes back in memory. Both calls take a **`content://` URI**: one it will write the
 * downloaded message into, one it will read the outgoing message out of. That work happens in the
 * phone process, under a different UID, so there has to be a provider for it to open.
 *
 * ## Why it is not exported
 *
 * The usual implementation of this declares `exported="true"` and stops thinking about it, which
 * makes every picture message on the phone readable by any installed app for as long as its file is
 * in the cache. This one is **not exported**. It carries `grantUriPermissions`, and the transport
 * grants exactly one URI to exactly the two system packages that need it, for the duration of one
 * transfer, and revokes it afterwards ([MmsTransport.grant] / [MmsTransport.revoke]).
 *
 * Everything else about it is refusal: it cannot be queried, it has no type worth advertising, and
 * `insert`, `update` and `delete` do nothing. A provider is a surface, and this one is a keyhole.
 *
 * ## The files
 *
 * They live in the cache, under one directory, named by the transaction they belong to. They are a
 * *transfer buffer* rather than storage — the message itself goes into the platform's own provider
 * the moment it is decoded — so they are deleted as soon as the transfer ends, and anything left
 * behind by a transfer that never ended is swept on the next one ([sweep]).
 */
class MmsFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * The only method that does anything.
     *
     * The name is taken from the last path segment and checked, because a provider that resolves
     * `../../databases/lifeops.db` is a provider that hands the phone process an arbitrary file
     * from this app's data directory.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val app = context ?: return null
        val name = uri.lastPathSegment?.takeIf(::isSafeName) ?: return null
        val file = File(dir(app), name)
        if (!file.exists()) file.createNewFile()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {

        /** Distinct from every other provider in this one process. */
        private const val AUTHORITY_SUFFIX = ".utilities.mms"

        private const val DIR_NAME = "mms-transfer"

        /** A buffer older than this belonged to a transfer that never finished. */
        private const val STALE_MS = 30 * 60 * 1000L

        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        fun dir(context: Context): File =
            File(context.applicationContext.cacheDir, DIR_NAME).apply { mkdirs() }

        fun file(context: Context, name: String): File = File(dir(context), name)

        fun uriFor(context: Context, name: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build()

        /**
         * A name for one transfer's buffer.
         *
         * Derived from the clock and a hash rather than from the transaction id itself: a
         * transaction id comes from the network, ends up as a filename, and there is no reason to
         * let a stranger choose one.
         */
        fun nameFor(prefix: String, seed: String): String =
            "$prefix-${System.currentTimeMillis()}-${(seed.hashCode().toLong() and 0xFFFFFFFFL).toString(16)}.pdu"

        fun delete(context: Context, name: String) {
            runCatching { file(context, name).delete() }
        }

        /** Throw away buffers left by transfers that never ended. Called before each new one. */
        fun sweep(context: Context) {
            runCatching {
                val cutoff = System.currentTimeMillis() - STALE_MS
                dir(context).listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) file.delete()
                }
            }
        }

        /** A single path segment, and nothing that could climb out of the directory. */
        private fun isSafeName(name: String): Boolean =
            name.isNotBlank() &&
                name.length <= 128 &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains("..") &&
                name.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }
    }
}
