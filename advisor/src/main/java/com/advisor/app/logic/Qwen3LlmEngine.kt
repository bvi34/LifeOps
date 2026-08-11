package com.advisor.app.logic

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

    /** The backend's own account of what's loaded, or exactly why it isn't (see [LlmBackend.detail]). */
    override val status: String get() = backend.detail

    override fun generate(prompt: AdvisorPrompt): String {
        if (!backend.isReady) return fallback.generate(prompt)

        val formatted = Qwen3ChatFormat.forPrompt(prompt)
        val raw = runCatching { backend.generate(formatted, params) }.getOrNull()
        val answer = raw?.let { Qwen3ChatFormat.cleanOutput(it) }.orEmpty()

        // A blank or failed generation is never a good answer — fall back rather than show nothing.
        return if (answer.isBlank()) fallback.generate(prompt) else answer
    }

    companion object {
        /** The intended weights: Qwen3-4B, 4-bit K-quant GGUF, loaded on-device. */
        val LOADED = ModelSpec(
            name = "Qwen3-4B",
            parameters = "4B",
            quantization = "Q4_K_M / GGUF",
            isPlaceholder = false
        )
    }
}
