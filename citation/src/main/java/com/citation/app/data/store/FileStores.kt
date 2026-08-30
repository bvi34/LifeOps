package com.citation.app.data.store

import android.content.Context
import com.citation.core.model.SourceType
import com.citation.core.reader.ReaderFont
import com.citation.core.reader.ReaderFontNames
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

    /** Size (bytes) of an owned file (EPUB/PDF) in the sovereign store, or 0 if absent. */
    fun ownedFileSize(bookKey: String, extension: String): Long =
        File(sovereignDir, "$bookKey.$extension").let { if (it.exists()) it.length() else 0L }

    /** Total bytes of a serial's cached chapter bodies in the disposable store. */
    fun borrowedTotalSize(bookKey: String): Long =
        File(disposableDir, bookKey).listFiles()?.sumOf { it.length() } ?: 0L

    /** Drop a serial's entire borrowed cache directory (used when the serial is deleted/uncached). */
    fun deleteBorrowedFiction(bookKey: String): Boolean =
        File(disposableDir, bookKey).deleteRecursively()

    /** Remove an owned file (EPUB/PDF) from the sovereign store (used when the book is deleted). */
    fun deleteOwned(bookKey: String, extension: String): Boolean =
        File(sovereignDir, "$bookKey.$extension").let { it.exists() && it.delete() }

    // --- Book assets: covers and illustrations ------------------------------------------------
    //
    // Images extracted from an owned EPUB live beside the book in the sovereign store, because
    // eviction must not be able to reach them: a book whose plates had been reclaimed would render
    // with holes in it. They are still *derived* — re-parsing the kept .epub reproduces every one —
    // so losing them is recoverable, unlike a note.

    private fun assetDir(bookKey: String): File = File(File(sovereignDir, bookKey), "assets")

    /**
     * The file an image reference maps to.
     *
     * Named by a digest of the source reference rather than by its path, which settles three
     * problems at once: an EPUB href can contain `..` or an absolute path (a zip is not a trusted
     * archive), it can contain characters the filesystem rejects, and it can nest arbitrarily deep.
     * A flat directory of digest-named files has none of those failure modes, and the mapping stays
     * a pure function of the reference so nothing has to be recorded to find an image again.
     */
    fun bookAssetFile(bookKey: String, src: String): File {
        val extension = src.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
        val name = digest(src) + if (extension != null) ".$extension" else ""
        return File(assetDir(bookKey), name)
    }

    /** Store one extracted image, returning where it landed. */
    fun writeBookAsset(bookKey: String, src: String, bytes: ByteArray): File {
        val file = bookAssetFile(bookKey, src)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /** The stored image for a reference, or null if this book has none. */
    fun readBookAsset(bookKey: String, src: String): File? =
        bookAssetFile(bookKey, src).takeIf { it.exists() }

    /** The book's cover file, or null. Kept at a fixed name so the library can find it cheaply. */
    fun coverFile(bookKey: String): File? =
        File(File(sovereignDir, bookKey), "cover").takeIf { it.exists() && it.length() > 0 }

    fun writeCover(bookKey: String, bytes: ByteArray): File {
        val file = File(File(sovereignDir, bookKey), "cover")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /** Total bytes of a book's stored images, for the storage screen. */
    fun bookAssetsSize(bookKey: String): Long {
        val dir = File(sovereignDir, bookKey)
        if (!dir.exists()) return 0
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** Drop a book's whole asset directory (used when the book is deleted). */
    fun deleteBookAssets(bookKey: String): Boolean =
        File(sovereignDir, bookKey).deleteRecursively()

    // --- Reader fonts --------------------------------------------------------------------------

    private val fontDir: File get() = File(sovereignDir, "fonts").apply { mkdirs() }

    /**
     * Store a font the reader chose, named by a digest of its **bytes**.
     *
     * Content-addressed rather than named after the file it came from: picking the same font twice
     * — from Downloads, then from a file manager — must not leave two copies, and a face whose file
     * name changed is still the same face. It lives in the sovereign store because a book set in a
     * font that vanishes is a book that changes appearance for no reason the reader can see.
     *
     * [pickedName] is the file the reader chose it from, used as its first label — the digest that
     * names the file is unusable as one. Never written over a label that already exists: adding the
     * same face a second time, from another folder, must not undo a rename.
     */
    fun writeReaderFont(bytes: ByteArray, extension: String, pickedName: String? = null): File {
        val safe = extension.filter { it.isLetterOrDigit() }.lowercase().take(4).ifEmpty { "ttf" }
        val file = File(fontDir, digestBytes(bytes) + "." + safe)
        if (!file.exists()) file.writeBytes(bytes)
        val label = labelFile(file)
        if (!label.exists()) {
            ReaderFontNames.fromFileName(pickedName.orEmpty())
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { label.writeText(it) } }
        }
        return file
    }

    /** Every stored font with the name it goes by, for the picker to list what is already here. */
    fun readerFonts(): List<ReaderFont> = fontDir.listFiles().orEmpty()
        .filter { it.isFile && !it.name.endsWith(LABEL_SUFFIX) }
        .map { ReaderFont(it.absolutePath, ReaderFontNames.of(it.absolutePath, readLabel(it))) }
        // Sorted by the name the reader sees rather than by the digest, which orders them at random.
        .sortedBy { it.name.lowercase() }

    /**
     * Rename a stored font.
     *
     * The label is a sidecar file rather than a rename of the font itself: the font's name *is* its
     * content digest, and every book already set in it refers to it by that path. Renaming the file
     * would silently unset the face of every such book.
     */
    fun renameReaderFont(path: String, name: String): Boolean {
        val font = readerFontAt(path) ?: return false
        val clean = ReaderFontNames.clean(name)
        if (clean.isEmpty()) return false
        return runCatching { labelFile(font).writeText(clean) }.isSuccess
    }

    /** Remove a stored font and the name that went with it. */
    fun deleteReaderFont(path: String): Boolean {
        val font = readerFontAt(path) ?: return false
        // The label goes first: an orphaned one is harmless, but a font left behind with its name
        // deleted would come back into the list looking like a font the reader never added.
        runCatching { labelFile(font).delete() }
        return font.delete()
    }

    /**
     * The stored font at [path], or null.
     *
     * The parent check is the whole point: paths reach here from settings rows written by older
     * builds and from the UI, and nothing outside the font directory may be renamed or deleted
     * through them.
     */
    private fun readerFontAt(path: String): File? = File(path).takeIf {
        it.parentFile == fontDir && it.isFile && !it.name.endsWith(LABEL_SUFFIX)
    }

    private fun labelFile(font: File): File = File(font.parentFile, font.name + LABEL_SUFFIX)

    private fun readLabel(font: File): String? =
        runCatching { labelFile(font).takeIf { it.exists() }?.readText() }.getOrNull()

    private fun digestBytes(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private fun digest(value: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
}

/**
 * Suffix for the sidecar file holding a font's name.
 *
 * Safe to filter the font directory on: a stored font's extension is sanitized to at most four
 * alphanumeric characters, so no font file can ever end in `.label`.
 */
private const val LABEL_SUFFIX = ".label"
