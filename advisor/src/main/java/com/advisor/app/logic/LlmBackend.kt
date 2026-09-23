package com.advisor.app.logic

/**
 * Sampling and limits handed to a backend for one generation. Small and model-agnostic — the defaults
 * are sane for a grounded, factual assistant (low-ish creativity, bounded length) and match the values
 * the Qwen3 team recommends for non-thinking mode.
 */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    /** Sequences that end generation early — Qwen3's turn/document end tokens. */
    val stop: List<String> = listOf("<|im_end|>", "<|endoftext|>")
)

/**
 * The narrow native seam Advisor's model runs behind. A backend loads a GGUF model once and turns a
 * fully-formatted prompt string into text, entirely on-device. It is kept framework-free so
 * [Qwen3LlmEngine] and its tests depend only on this interface, never on the JNI / llama.cpp
 * implementation ([com.advisor.app.llm.LlamaCppBackend]) that provides it in the app.
 */
interface LlmBackend {

    /** True once the model can run — its weights are present and loadable on this device. */
    val isReady: Boolean

    /** A short label for what is loaded (e.g. the resolved GGUF filename), for diagnostics. */
    val detail: String

    /**
     * How many tokens of context this model has, for callers sizing a prompt to it.
     *
     * Not a constant, and not something to assume: a backend may end up with a different window than
     * it asked for. The default is the conservative floor — building a prompt for a window larger than
     * the real one is how a prompt gets truncated, and truncation takes the *front*, which is where
     * the system instruction lives.
     */
    val contextTokens: Int get() = DEFAULT_CONTEXT_TOKENS

    /** Run [prompt] (already in the model's chat format) to completion and return the raw text. */
    fun generate(prompt: String, params: GenerationParams = GenerationParams()): String

    /**
     * As [generate], but hands each piece of the answer to [onToken] as it is produced, and still
     * returns the whole raw text. The pieces are raw model output in order, so concatenating them
     * reconstructs the return value — they are not cleaned, and a piece is not a word or a token, just
     * however many bytes were settled at that moment.
     *
     * A generation on a phone runs for tens of seconds, so whether a caller can show it arriving is
     * the difference between a spinner and a reply. The default ignores [onToken] and delegates, so a
     * backend that cannot stream (or a test fake) needs no implementation and simply reports its
     * answer at the end.
     */
    fun generate(prompt: String, params: GenerationParams, onToken: (String) -> Unit): String =
        generate(prompt, params)

    /**
     * Load the model now, if it isn't already, so the first question doesn't pay for it.
     *
     * Loading a multi-gigabyte GGUF is tens of seconds of I/O and page allocation, and it happened
     * inside the first `generate` — landing entirely on the first question the user asked, on top of
     * that question's own retrieval and inference. Doing it when the assistant is opened instead moves
     * that cost to a moment when nobody is waiting on an answer.
     *
     * Blocking, and safe to call repeatedly: a backend that is already loaded returns immediately, and
     * a concurrent question simply waits for the same load rather than starting a second one. The
     * default does nothing, which is right for a backend with nothing to load.
     */
    fun warmUp() {}

    /**
     * Whether the weights are in memory right now — as opposed to [isReady], which only says they
     * *could* be. The difference is what the memory policy turns on: a resident model's cost is
     * already paid, a cold one's is about to be.
     */
    val isLoaded: Boolean get() = isReady

    /** Size of the weights this backend would load, in bytes; 0 when there is nothing to load. */
    val modelBytes: Long get() = 0L

    /**
     * Give the weights' memory back if nothing is using it, because the system asked. Must not block:
     * it is called from the main thread, and a generation in progress keeps its model — the next
     * question would only have to load it again. Returns whether anything was released. The next
     * [generate] or [warmUp] loads the model again.
     */
    fun trim(): Boolean = false

    /**
     * Stop a generation in progress as soon as the model can, keeping what it has written so far.
     * [reason] is a short phrase for the answer's footnote. Safe to call from any thread and when
     * nothing is running, in which case it does nothing.
     */
    fun interrupt(reason: String) {}

    /**
     * Why the last generation was stopped early, if [interrupt] stopped it — cleared by reading, so a
     * reason never outlives the answer it belongs to.
     */
    fun takeInterruption(): String? = null

    /** Release native resources. Safe to call more than once. */
    fun close() {}

    companion object {
        /** The smallest context any backend here is built with; assumed until one says otherwise. */
        const val DEFAULT_CONTEXT_TOKENS = 2048

        /** A backend that never loads; the engine then falls back to the extractive placeholder. */
        val NONE: LlmBackend = object : LlmBackend {
            override val isReady = false
            override val detail = "no backend"
            override fun generate(prompt: String, params: GenerationParams): String = ""
        }
    }
}
