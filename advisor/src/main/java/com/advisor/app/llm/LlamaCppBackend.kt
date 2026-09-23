package com.advisor.app.llm

import android.util.Log
import com.advisor.app.logic.GenerationParams
import com.advisor.app.logic.LlmBackend
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [LlmBackend] backed by llama.cpp's native inference, loaded over JNI. It loads whatever Qwen3-4B
 * GGUF [AdvisorModelStore] reports as installed — a file the user imported in-app, or one `adb push`ed
 * onto the device — through the `advisor-llm` native library, and runs a completion for each prompt,
 * all on-device with no network.
 *
 * The native library and multi-gigabyte weights are provisioned separately, so every native entry
 * point is guarded: if the `.so` or the `.gguf` isn't present the backend reports `isReady = false`
 * and [com.advisor.app.logic.Qwen3LlmEngine] falls back to its extractive placeholder. A freshly
 * imported model is detected on the next question (the store is re-checked until a model is loaded),
 * so importing weights activates the model with no restart and no code change.
 */
class LlamaCppBackend(private val modelStore: AdvisorModelStore) : LlmBackend {

    @Volatile private var handle: Long = 0L
    @Volatile private var failedSignature: String? = null
    @Volatile private var interruption: String? = null

    /**
     * Held for the whole of a load, a generation and a free, so the weights can never be freed out
     * from under a running model — which [trim], arriving from the system on the main thread at any
     * moment, would otherwise do. A lock rather than `synchronized` for one reason: [trim] must be
     * able to *not* wait.
     */
    private val lock = ReentrantLock()

    /**
     * Guards the handle against [interrupt], which does not take [lock] (it exists to reach a
     * generation that holds it). Freeing takes both, so a stop can never land on a freed model.
     */
    private val handleGuard = Any()

    override val isReady: Boolean
        get() {
            if (!NATIVE_AVAILABLE) return false
            if (handle != 0L) return true
            val file = modelStore.installedModel() ?: return false
            return signature(file.absolutePath, file.length()) != failedSignature
        }

    override val detail: String
        get() {
            val file = modelStore.installedModel()
            return when {
                !NATIVE_AVAILABLE -> "native llama.cpp library not in this build"
                file == null -> "no Qwen3-4B GGUF installed"
                handle == 0L && failedSignature != null -> "model failed to load"
                else -> file.name
            }
        }

    override fun generate(prompt: String, params: GenerationParams): String =
        run(prompt, params, sink = null)

    override fun generate(
        prompt: String,
        params: GenerationParams,
        onToken: (String) -> Unit
    ): String = run(prompt, params, TokenSink(onToken))

    /**
     * The loaded model's real context length, or the conservative floor until one is loaded. Reading
     * it does not force a load: a caller sizing a prompt before warm-up finishes should get the safe
     * answer, not block for gigabytes of I/O.
     */
    override val contextTokens: Int
        get() {
            if (handle == 0L) return LlmBackend.DEFAULT_CONTEXT_TOKENS
            val reported = runCatching { nativeContextTokens(handle) }.getOrDefault(0)
            return if (reported > 0) reported else LlmBackend.DEFAULT_CONTEXT_TOKENS
        }

    override fun warmUp() {
        if (!lock.withLock { ensureLoaded() }) Log.i(TAG, "Warm-up: no model to load; staying on the placeholder.")
    }

    override val isLoaded: Boolean get() = handle != 0L

    override val modelBytes: Long get() = modelStore.installedModel()?.length() ?: 0L

    private fun run(prompt: String, params: GenerationParams, sink: TokenSink?): String = lock.withLock {
        if (!ensureLoaded()) return ""
        interruption = null
        Log.i(TAG, "Qwen3 backend boundary: chars=${prompt.length} hash=${sha256(prompt)}")
        Log.i(TAG, "Qwen3 backend boundary head=${prompt.take(120).replace("\n", "\\n")}")
        runCatching {
            nativeGenerate(
                handle, prompt, params.maxTokens, params.temperature,
                params.topP, params.topK, params.stop.toTypedArray(), sink
            )
        }.getOrElse {
            Log.w(TAG, "Qwen3 generation failed; falling back.", it)
            ""
        }
    }

    /**
     * Free the weights unless a load or a generation holds them. Never waits: the system calls this
     * on the main thread, and a generation can run for a minute. A busy model is simply kept — it is
     * about to answer, and freeing it would only make the next question load it again.
     */
    override fun trim(): Boolean {
        if (handle == 0L || !lock.tryLock()) return false
        try {
            if (handle == 0L) return false
            free()
            Log.i(TAG, "Released the model's memory at the system's request; it reloads on the next question.")
            return true
        } finally {
            lock.unlock()
        }
    }

    override fun interrupt(reason: String) {
        synchronized(handleGuard) {
            val h = handle
            if (h == 0L) return
            // Recorded before the stop so the generation that returns because of it finds it.
            interruption = reason
            val stopped = runCatching { nativeStop(h) }.getOrDefault(false)
            if (stopped) Log.w(TAG, "Stopping generation early: $reason") else interruption = null
        }
    }

    override fun takeInterruption(): String? = interruption.also { interruption = null }

    /**
     * What the native side calls as each piece of the answer settles.
     *
     * Bytes, not a `String`, because a token boundary is not a character boundary — a single emoji is
     * routinely split across two tokens — and JNI's `NewStringUTF` expects *modified* UTF-8, which the
     * four-byte sequences emoji are made of are not. Native holds back an incomplete sequence and
     * hands over whole ones; decoding them belongs here, where the standard decoder can do it.
     *
     * Nothing in Kotlin calls [onToken] — the native side resolves it by name — so a shrinker would
     * be free to rename or remove it and streaming would silently stop with no error anywhere.
     * `consumer-rules.pro` keeps it; the signature there and the one native looks up must agree.
     */
    private class TokenSink(private val emit: (String) -> Unit) {
        fun onToken(utf8: ByteArray) = emit(String(utf8, Charsets.UTF_8))
    }

    override fun close() {
        lock.withLock { free() }
    }

    /** Callers hold [lock]. */
    private fun free() {
        synchronized(handleGuard) {
            val h = handle
            handle = 0L
            if (h != 0L) runCatching { nativeFree(h) }
        }
    }

    /** Callers hold [lock], so a load never races a free or a second load. */
    private fun ensureLoaded(): Boolean {
        if (handle != 0L) return true
        if (!NATIVE_AVAILABLE) return false
        val file = modelStore.installedModel() ?: return false
        val sig = signature(file.absolutePath, file.length())
        if (sig == failedSignature) return false
        val loaded = runCatching { nativeLoad(file.absolutePath) }.getOrElse {
            Log.w(TAG, "Failed to load Qwen3-4B GGUF at ${file.absolutePath}", it)
            0L
        }
        if (loaded == 0L) {
            failedSignature = sig
            return false
        }
        synchronized(handleGuard) { handle = loaded }
        failedSignature = null
        return true
    }

    private fun signature(path: String, size: Long): String = "$path:$size"

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, stop: Array<String>, listener: TokenSink?
    ): String
    private external fun nativeContextTokens(handle: Long): Int
    private external fun nativeFree(handle: Long)
    /** Asks a running generation to stop at its next graph node; false when none is running. */
    private external fun nativeStop(handle: Long): Boolean

    companion object {
        private const val TAG = "LlamaCppBackend"
        private val NATIVE_AVAILABLE: Boolean = runCatching {
            System.loadLibrary("advisor-llm")
            true
        }.getOrElse {
            Log.i(TAG, "Native llama.cpp backend not present; Advisor will use the placeholder engine.")
            false
        }
    }
}
