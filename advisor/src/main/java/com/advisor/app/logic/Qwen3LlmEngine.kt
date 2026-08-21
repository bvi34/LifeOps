package com.advisor.app.logic

import android.util.Log

/**
 * The Advisor's real generation step, backed by a local **Qwen3-4B** model (Q4_K_M GGUF) running fully
 * on-device through an [LlmBackend]. It formats the assembled [AdvisorPrompt] with Qwen3's chat
 * template ([Qwen3ChatFormat]), runs it through the backend, and cleans the completion back into a
 * grounded answer — the same prompt, retrieval and citations the rest of the pipeline already built.
 *
 * If the backend isn't ready — the multi-gigabyte weights aren't on the device yet — or a generation
 * comes back empty or throws, it transparently falls back to the deterministic [PlaceholderLlmEngine]
 * so the pipeline still produces a grounded, cited answer instead of failing. [spec] reports which of
 * the two actually answered, so the UI's model card stays honest.
 */
class Qwen3LlmEngine(
    private val backend: LlmBackend,
    private val params: GenerationParams = GenerationParams(),
    private val fallback: LocalLlmEngine = PlaceholderLlmEngine()
) : LocalLlmEngine {

    override val spec: ModelSpec
        get() = if (backend.isReady) LOADED else fallback.spec

    override val status: String get() = backend.detail

    override fun generate(prompt: AdvisorPrompt): String = run(prompt, onPartial = null)

    override fun generate(prompt: AdvisorPrompt, onPartial: (String) -> Unit): String =
        run(prompt, onPartial)

    override fun warmUp() = backend.warmUp()

    private fun run(prompt: AdvisorPrompt, onPartial: ((String) -> Unit)?): String {
        if (!backend.isReady) return fallback.generate(prompt)
        val formatted = Qwen3ChatFormat.forPrompt(prompt)
        Log.i(TAG, "Qwen3 prompt boundary: chars=${formatted.length} hash=${sha256(formatted)}")
        Log.i(TAG, "Qwen3 prompt boundary head=${formatted.take(120).replace("\n", "\\n")}")
        val raw = runCatching {
            if (onPartial == null) {
                backend.generate(formatted, params)
            } else {
                // The backend streams raw model output; the chat format is what makes it an answer.
                // Cleaning the whole accumulation each time — rather than the newest piece — is what
                // lets a retraction (a control token completing, a think block closing) simply
                // produce a shorter string instead of needing to be undone downstream.
                val seen = StringBuilder()
                backend.generate(formatted, params) { piece ->
                    seen.append(piece)
                    onPartial(Qwen3ChatFormat.cleanPartial(seen.toString()))
                }
            }
        }.getOrNull()
        val answer = raw?.let { Qwen3ChatFormat.cleanOutput(it) }.orEmpty()
        return if (answer.isBlank()) fallback.generate(prompt) else answer
    }

    companion object {
        val LOADED = ModelSpec(
            name = "Qwen3-4B",
            parameters = "4B",
            quantization = "Q4_K_M / GGUF",
            isPlaceholder = false
        )

        private const val TAG = "Qwen3LlmEngine"

        private fun sha256(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }
}
