package com.citation.app.data.store

import android.content.Context
import com.citation.core.model.SourceType
import com.citation.core.store.Ownership
import com.citation.core.store.Store
import java.io.File

/**
 * The **two physical stores split by ownership**, as real directories.
 *
 * The whole point is structural: the eviction job is only ever handed [disposableDir], so it
 * *cannot* reach owned files or notes no matter what a bug does elsewhere — they live under
 * [sovereignDir], which the evictor is never given a handle to. [storeDirFor] routes content by the
 * pure [Ownership] policy from :core, keeping the classification in one tested place.
 *
 * @property sovereignDir owned files (imported EPUB/PDF), never auto-evicted, backed up.
 * @property disposableDir borrowed cache (RR chapter bodies, prefetch) — the only dir eviction walks.
 */
class FileStores(context: Context) {

    val sovereignDir: File = File(context.filesDir, "sovereign").apply { mkdirs() }
    val disposableDir: File = File(context.cacheDir, "disposable").apply { mkdirs() }

    /** The directory a piece of content for [source] belongs in, per ownership policy. */
    fun storeDirFor(source: SourceType): File = when (Ownership.contentStore(source)) {
        Store.SOVEREIGN -> sovereignDir
        Store.DISPOSABLE -> disposableDir
    }

    /** Persist an imported owned file (EPUB/PDF) into the sovereign store, returning its path. */
    fun writeOwned(bookKey: String, extension: String, bytes: ByteArray): File {
        val file = File(sovereignDir, "$bookKey.$extension")
        file.writeBytes(bytes)
        return file
    }

    /** Write a borrowed chapter body into the disposable cache. */
    fun writeBorrowedChapter(bookKey: String, ordinal: Int, text: String): File {
        val dir = File(disposableDir, bookKey).apply { mkdirs() }
        val file = File(dir, "$ordinal.txt")
        file.writeText(text)
        return file
    }

    fun readBorrowedChapter(bookKey: String, ordinal: Int): String? {
        val file = File(File(disposableDir, bookKey), "$ordinal.txt")
        return if (file.exists()) file.readText() else null
    }

    /** Reclaim one borrowed chapter body from the disposable cache. Deletes succeed even when full. */
    fun deleteBorrowedChapter(bookKey: String, ordinal: Int): Boolean {
        val file = File(File(disposableDir, bookKey), "$ordinal.txt")
        return file.exists() && file.delete()
    }
}
