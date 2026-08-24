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
    private val fallback: LocalLlmEngine = PlaceholderLlmEngine(),
    /**
     * Where the prompt-boundary diagnostics go. A sink rather than a direct `android.util.Log` call
     * because everything under `logic/` is framework-free and JVM-tested; the Android log is wired in
     * by [com.advisor.app.AdvisorApp]. Defaults to discarding, so a test never needs a mocked
     * framework to exercise generation.
     */
    private val log: (String) -> Unit = {}
) : LocalLlmEngine {

    /**
     * What is actually running. Read from the loaded file's name rather than being a constant: the
     * model card's job is to be honest about which model is answering, and any generation GGUF can be
     * installed — reporting all of them as the one this class was written against would make the card
     * confidently wrong about the thing it exists to report.
     */
    override val spec: ModelSpec
        get() = if (backend.isReady) GgufName.specOf(backend.detail) else fallback.spec

    override val status: String get() = backend.detail

    override fun generate(prompt: AdvisorPrompt): String = run(prompt, onPartial = null)

    override fun generate(prompt: AdvisorPrompt, onPartial: (String) -> Unit): String =
        run(prompt, onPartial)

    override fun warmUp() = backend.warmUp()

    override val contextTokens: Int get() = backend.contextTokens

    private fun run(prompt: AdvisorPrompt, onPartial: ((String) -> Unit)?): String {
        if (!backend.isReady) return fallback.generate(prompt)
        val formatted = Qwen3ChatFormat.forPrompt(prompt)
        log("Qwen3 prompt boundary: chars=${formatted.length} hash=${sha256(formatted)}")
        log("Qwen3 prompt boundary head=${formatted.take(120).replace("\n", "\\n")}")
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
        /** The Android log tag [com.advisor.app.AdvisorApp] stamps the diagnostics with. */
        const val TAG = "Qwen3LlmEngine"

        private fun sha256(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }
}
