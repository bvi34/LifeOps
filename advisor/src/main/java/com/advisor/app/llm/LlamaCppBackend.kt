package com.advisor.app.llm

import android.content.Context
import android.util.Log
import com.advisor.app.logic.GenerationParams
import com.advisor.app.logic.LlmBackend
import java.io.File

/**
 * A [LlmBackend] backed by llama.cpp's native inference, loaded over JNI. It looks for a Qwen3-4B GGUF
 * that has been placed on the device (bundled under the app's `files/models`, or side-loaded into
 * external files), loads it once through the `advisor-llm` native library, and runs a completion for
 * each prompt — all on-device, no network.
 *
 * The native library and multi-gigabyte weights are provisioned out of band, so every native entry
 * point is guarded: if the `.so` or the `.gguf` isn't present the backend reports `isReady = false`
 * and [com.advisor.app.logic.Qwen3LlmEngine] falls back to its extractive placeholder. That keeps the
 * app fully functional on a device that hasn't been given the model yet, and makes wiring the real
 * weights a drop-in — no code change, just the file.
 */
class LlamaCppBackend(context: Context) : LlmBackend {

    private val appContext = context.applicationContext
    private val modelFile: File? by lazy { locateModel(appContext) }

    @Volatile private var handle: Long = 0L
    @Volatile private var loadFailed: Boolean = false
    private val lock = Any()

    override val isReady: Boolean
        get() = NATIVE_AVAILABLE && modelFile != null && !loadFailed

    override val detail: String
        get() = when {
            !NATIVE_AVAILABLE -> "native llama.cpp library unavailable"
            modelFile == null -> "no Qwen3-4B GGUF found"
            loadFailed -> "model failed to load"
            else -> modelFile!!.name
        }

    override fun generate(prompt: String, params: GenerationParams): String {
        if (!ensureLoaded()) return ""
        return runCatching {
            nativeGenerate(
                handle, prompt, params.maxTokens, params.temperature,
                params.topP, params.topK, params.stop.toTypedArray()
            )
        }.getOrElse {
            Log.w(TAG, "Qwen3 generation failed; falling back.", it)
            ""
        }
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { nativeFree(handle) }
                handle = 0L
            }
        }
    }

    /** Load the weights on first use (off the main thread — generation is already dispatched there). */
    private fun ensureLoaded(): Boolean {
        if (handle != 0L) return true
        if (!NATIVE_AVAILABLE || loadFailed) return false
        val file = modelFile ?: return false
        synchronized(lock) {
            if (handle != 0L) return true
            handle = runCatching { nativeLoad(file.absolutePath) }.getOrElse {
                Log.w(TAG, "Failed to load Qwen3-4B GGUF at ${file.absolutePath}", it)
                0L
            }
            if (handle == 0L) loadFailed = true
            return handle != 0L
        }
    }

    // --- JNI: implemented by the `advisor-llm` native library (llama.cpp) ---

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, stop: Array<String>
    ): String
    private external fun nativeFree(handle: Long)

    companion object {
        private const val TAG = "LlamaCppBackend"

        /** True once the native library is present; false (and logged) when it isn't bundled yet. */
        private val NATIVE_AVAILABLE: Boolean = runCatching {
            System.loadLibrary("advisor-llm")
            true
        }.getOrElse {
            Log.i(TAG, "Native llama.cpp backend not present; Advisor will use the placeholder engine.")
            false
        }

        /** Search the app's model directories for a Qwen3-4B GGUF, most-specific filename first. */
        private fun locateModel(context: Context): File? {
            val dirs = buildList {
                add(File(context.filesDir, "models"))
                context.getExternalFilesDir("models")?.let { add(it) }
            }
            for (dir in dirs) {
                dir.listFiles()
                    ?.filter { it.isFile && it.length() > 0 && isQwen3Gguf(it.name) }
                    ?.minByOrNull { it.name.length } // shortest name ≈ the canonical drop-in
                    ?.let { return it }
            }
            return null
        }

        private fun isQwen3Gguf(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("qwen3-4b") && lower.endsWith(".gguf")
        }
    }
}
