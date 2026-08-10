package com.advisor.app.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Owns Advisor's on-device **embedding** model file, exactly as [AdvisorModelStore] owns the Qwen3-4B
 * generation weights — a separate, small GGUF (a sentence-embedding model, tens to a couple hundred
 * MB) the user imports from device storage. It lives beside the generation model in the same private
 * `files/models` directory but is keyed on a distinct filename marker, so importing one never disturbs
 * the other.
 *
 * Keeping the embedder in its own file (rather than reusing the 4B for embeddings) means small, fast,
 * good-quality vectors — and the same promise holds: the file is *copied in* from a document the user
 * chose, so there is no `INTERNET` permission and nothing leaves the device.
 * [com.advisor.app.llm.LlamaCppEmbedder] loads whatever this store reports as installed, so an import
 * is picked up on the next question.
 */
class EmbeddingModelStore(context: Context) {

    private val appContext = context.applicationContext

    /** Same private directory the generation weights use; the two are told apart by filename. */
    fun modelsDir(): File = File(appContext.filesDir, "models")

    /**
     * The installed embedding GGUF, if any — internal storage first, then an `adb push`ed copy under
     * external files. The shortest matching name wins (the canonical drop-in).
     */
    fun installedModel(): File? {
        val dirs = listOfNotNull(modelsDir(), appContext.getExternalFilesDir("models"))
        for (dir in dirs) {
            dir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && isEmbeddingGguf(it.name) }
                ?.minByOrNull { it.name.length }
                ?.let { return it }
        }
        return null
    }

    fun isInstalled(): Boolean = installedModel() != null

    fun info(): AdvisorModelInfo {
        val f = installedModel()
        return AdvisorModelInfo(installed = f != null, fileName = f?.name, sizeBytes = f?.length() ?: 0L)
    }

    /**
     * Copy the GGUF at [uri] into internal storage, reporting progress. Streams to a `.embpart` file
     * and renames on success so a cancelled import never leaves a truncated file the backend would try
     * to load. Returns the final file. Runs on IO.
     */
    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val srcName = queryName(uri)
            require(srcName == null || srcName.endsWith(".gguf", ignoreCase = true)) {
                "Pick a .gguf model file (got \"$srcName\")."
            }
            val total = querySize(uri)
            val targetName = resolveTargetName(srcName)
            val dir = modelsDir().apply { mkdirs() }

            if (total > 0) {
                val free = dir.usableSpace
                if (free in 0 until (total + SLACK_BYTES)) {
                    throw IOException(
                        "Not enough space: the model needs ${AdvisorModelStore.humanBytes(total)}, " +
                            "only ${AdvisorModelStore.humanBytes(free)} free."
                    )
                }
            }

            val part = File(dir, "$targetName.embpart")
            val target = File(dir, targetName)
            part.delete()

            val input = resolver.openInputStream(uri)
                ?: throw IOException("Couldn't open the selected file.")
            input.use { source ->
                part.outputStream().use { output ->
                    val buf = ByteArray(1 shl 20) // 1 MiB
                    var copied = 0L
                    onProgress(0L, total)
                    while (true) {
                        val n = source.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        onProgress(copied, total)
                    }
                    output.flush()
                }
            }

            // One embedding model at a time: clear any previous embedding file, then commit the new one.
            dir.listFiles()
                ?.filter { it.name != part.name && (isEmbeddingGguf(it.name) || it.name.endsWith(".embpart")) }
                ?.forEach { it.delete() }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("Couldn't finalize the imported model.")
            }
            target
        }

    /** Remove the imported (internal) embedding model. Returns true if a file was deleted. */
    fun delete(): Boolean {
        var deleted = false
        modelsDir().listFiles()
            ?.filter { isEmbeddingGguf(it.name) || it.name.endsWith(".embpart") }
            ?.forEach { if (it.delete()) deleted = true }
        return deleted
    }

    /** Keep the imported name when it's clearly an embedding GGUF; otherwise use the canonical name. */
    private fun resolveTargetName(srcName: String?): String {
        val name = srcName?.substringAfterLast('/')?.trim().orEmpty()
        return if (isEmbeddingGguf(name)) name else DEFAULT_NAME
    }

    private fun queryName(uri: Uri): String? =
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun querySize(uri: Uri): Long =
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L

    companion object {
        const val DEFAULT_NAME = "advisor-embed.gguf"
        private const val SLACK_BYTES = 32L * 1024 * 1024

        /**
         * The filename contract the embedder keys on: any `.gguf` whose name marks it as an embedding
         * model. "embed" covers the canonical `advisor-embed.gguf` and common model names (bge, e5,
         * gte, minilm, nomic *embed* variants), while keeping it distinct from `qwen3-4b*.gguf`.
         */
        fun isEmbeddingGguf(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return lower.endsWith(".gguf") && MARKERS.any { it in lower }
        }

        private val MARKERS = listOf("embed", "bge", "gte-", "e5-", "minilm", "nomic")
    }
}
