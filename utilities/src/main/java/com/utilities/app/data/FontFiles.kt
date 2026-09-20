package com.utilities.app.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * A font file the household picked, copied in.
 *
 * ## Why a copy rather than the URI
 *
 * The obvious implementation keeps the `content://` URI and opens it when the surface is drawn, and
 * it is wrong twice over. A document grant does not survive a reinstall, so the font quietly stops
 * working on the day everything else comes back from a backup; and the keyboard is an *input
 * method*, drawn in a window with no activity, where opening a document provider belonging to
 * another app is a permission conversation nobody is there to have. So the bytes are copied into
 * this app's own directory, once, and everything afterwards is a plain file path.
 *
 * The file is named by the digest of its contents, which means picking the same font twice costs
 * nothing and two surfaces set in the same face share one copy — the same trick Citation uses for
 * reader fonts, and for the same reason.
 *
 * The directory is under `filesDir`, which the backup contributor sweeps whole, so a font the
 * household supplied travels with their settings rather than being a broken path on the new phone.
 */
object FontFiles {

    const val DIR_NAME = "utilities/fonts"

    /** A ten-megabyte "font" is not a font, and a keyboard must not try to parse one. */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** Copy [uri] in and return its path, or null if it could not be read or is not plausible. */
    fun copyIn(context: Context, uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val read = input.readBytes()
            if (read.size > MAX_BYTES) return null
            read
        } ?: return null
        if (bytes.isEmpty()) return null

        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "${digest(bytes)}.font")
        if (!file.exists()) file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    /** Every copied font, for the screen that lists them. */
    fun all(context: Context): List<File> =
        File(context.filesDir, DIR_NAME).listFiles()?.filter { it.isFile }.orEmpty()

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(16)
            .joinToString("") { "%02x".format(it) }
}
