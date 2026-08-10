package com.advisor.app.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** What the UI shows about the on-device model file (independent of whether the native lib is built). */
data class AdvisorModelInfo(
    val installed: Boolean,
    val fileName: String?,
    val sizeBytes: Long
) {
    /** e.g. "qwen3-4b-q4_k_m.gguf · 2.4 GB". */
    fun label(): String =
        if (!installed) "No model file installed"
        else "${fileName.orEmpty()} · ${AdvisorModelStore.humanBytes(sizeBytes)}"
}

/**
 * Owns Advisor's on-device model **file** — importing a Qwen3-4B GGUF the user picked from device
 * storage into the app's private `files/models`, reporting it, and removing it. This is the "native"
 * (in-app, no `adb`) way to provision the weights, and it keeps Advisor's promise intact: the file is
 * *copied in* from a document the user chose, so there is no `INTERNET` permission and nothing leaves
 * the device.
 *
 * [LlamaCppBackend] loads whatever this store reports as installed, so an import is picked up on the
 * next question without a code change or (for a fresh, never-loaded model) a restart.
 */
class AdvisorModelStore(context: Context) {

    private val appContext = context.applicationContext

    /** Internal, app-private directory the imported weights live in. */
    fun modelsDir(): File = File(appContext.filesDir, "models")

    /**
     * The installed Qwen3-4B GGUF, if any — internal storage first, then an `adb push`ed copy under
     * external files. The shortest matching name wins (the canonical drop-in).
     */
    fun installedModel(): File? {
        val dirs = listOfNotNull(modelsDir(), appContext.getExternalFilesDir("models"))
        for (dir in dirs) {
            dir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && isQwen3Gguf(it.name) }
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
     * Copy the GGUF at [uri] into internal storage, reporting progress. Streams to a `.part` file and
     * renames on success, so a cancelled or failed import never leaves a truncated file the backend
     * would try to load. Returns the final file. Runs on IO.
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

            // Don't start a multi-gigabyte copy that can't fit — fail early with a clear message.
            if (total > 0) {
                val free = dir.usableSpace
                if (free in 0 until (total + SLACK_BYTES)) {
                    throw IOException(
                        "Not enough space: the model needs ${humanBytes(total)}, only " +
                            "${humanBytes(free)} free."
                    )
                }
            }

            val part = File(dir, "$targetName.part")
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

            // One installed model at a time: clear any previous qwen3 file, then commit the new one.
            dir.listFiles()
                ?.filter { it.name != part.name && (isQwen3Gguf(it.name) || it.name.endsWith(".part")) }
                ?.forEach { it.delete() }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("Couldn't finalize the imported model.")
            }
            target
        }

    /** Remove the imported (internal) model. Returns true if a file was deleted. */
    fun delete(): Boolean {
        var deleted = false
        modelsDir().listFiles()
            ?.filter { isQwen3Gguf(it.name) || it.name.endsWith(".part") }
            ?.forEach { if (it.delete()) deleted = true }
        return deleted
    }

    /** Keep the imported name when it's clearly a Qwen3-4B GGUF; otherwise use the canonical name. */
    private fun resolveTargetName(srcName: String?): String {
        val name = srcName?.substringAfterLast('/')?.trim().orEmpty()
        return if (name.endsWith(".gguf", true) && name.lowercase(Locale.US).startsWith("qwen3-4b")) name
        else DEFAULT_NAME
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
        const val DEFAULT_NAME = "qwen3-4b-q4_k_m.gguf"
        private const val SLACK_BYTES = 64L * 1024 * 1024 // keep a little headroom

        fun isQwen3Gguf(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return lower.startsWith("qwen3-4b") && lower.endsWith(".gguf")
        }

        fun humanBytes(bytes: Long): String {
            if (bytes < 0) return "unknown size"
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var i = 0
            while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
            return String.format(Locale.US, "%.1f %s", value, units[i])
        }
    }
}
