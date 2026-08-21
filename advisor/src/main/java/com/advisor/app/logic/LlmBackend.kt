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

    /** Release native resources. Safe to call more than once. */
    fun close() {}

    companion object {
        /** A backend that never loads; the engine then falls back to the extractive placeholder. */
        val NONE: LlmBackend = object : LlmBackend {
            override val isReady = false
            override val detail = "no backend"
            override fun generate(prompt: String, params: GenerationParams): String = ""
        }
    }
}
