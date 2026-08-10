package com.advisor.app.logic

/**
 * Describes the language model behind the Advisor, so the UI can honestly show "what's running". It
 * describes either the real weights — a local **Qwen3-4B**, Q4_K_M GGUF, loaded on-device (see
 * [Qwen3LlmEngine]) — or the [PlaceholderLlmEngine] that stands in until those weights are present.
 */
data class ModelSpec(
    val name: String,
    val parameters: String,
    val quantization: String,
    val isPlaceholder: Boolean
) {
    /** A one-line "Gemma-2-2B · Q4_K_M" style label for the UI. */
    fun label(): String = buildString {
        append(name)
        append(" · ").append(parameters)
        append(" · ").append(quantization)
        if (isPlaceholder) append(" (placeholder)")
    }
}

/**
 * The **G**eneration step. The real implementation ([Qwen3LlmEngine]) loads a Qwen3-4B GGUF over
 * llama.cpp and runs the assembled prompt through it entirely on-device; [PlaceholderLlmEngine] stands
 * in until the weights are present. Everything upstream — permissions, retrieval, prompt assembly — is
 * model-agnostic, so this one interface is the only seam the model plugs into.
 */
interface LocalLlmEngine {
    val spec: ModelSpec

    /** Produce an answer for [prompt]. Must run fully on-device; no network, no I/O. */
    fun generate(prompt: AdvisorPrompt): String
}

/**
 * A deterministic stand-in for the local model. It does **not** invent language — it composes a
 * grounded, extractive answer straight from the retrieved context, so the end-to-end pipeline is
 * demonstrably working (permissions gate → retrieval → citations) while the real weights are still
 * being chosen. When there is no context it says so and points at the permission gate, because
 * "nothing granted / nothing matched" is the honest answer, not a hallucinated one.
 */
class PlaceholderLlmEngine : LocalLlmEngine {

    override val spec = ModelSpec(
        name = "sandbox-advisor-local",
        parameters = "2–4B",
        quantization = "Q4_K_M / GGUF",
        isPlaceholder = true
    )

    override fun generate(prompt: AdvisorPrompt): String {
        val hasContext = prompt.context.isNotEmpty()
        val hasMemory = prompt.memories.isNotEmpty()
        val hasProfiles = prompt.profiles.isNotEmpty()

        if (!hasContext && !hasMemory && !hasProfiles) {
            return "I couldn't find anything in your granted data or long-term memory to answer " +
                "that.\n\nThis is a placeholder assistant: it retrieves and cites your own records " +
                "but does not yet run a language model. Check that the relevant app is enabled in " +
                "Permissions, add a memory, or rephrase using words that appear in your data."
        }

        return buildString {
            if (hasContext) {
                val sourcesLine = prompt.context
                    .map { it.document.source }.distinct().joinToString(", ") { it.displayName }
                append("Based on your own ").append(sourcesLine).append(" data, here's what's relevant:\n")
                for (block in prompt.context) {
                    append("\n• ")
                    append(block.document.title.ifBlank { block.document.kind })
                    val detail = firstLine(block.excerpt)
                    if (detail.isNotBlank() && !detail.equals(block.document.title, ignoreCase = true)) {
                        append(" — ").append(detail)
                    }
                    append(" [").append(block.ref).append(']')
                }
            }

            if (hasMemory) {
                if (hasContext) append("\n\n")
                append("From long-term memory:")
                prompt.memories.forEachIndexed { index, memory ->
                    append("\n• ").append(firstLine(memory.content))
                    if (memory.tags.isNotEmpty()) append(" (").append(memory.tags.joinToString(", ")).append(')')
                    append(" [M").append(index + 1).append(']')
                }
            }

            if (hasProfiles) {
                if (hasContext || hasMemory) append("\n\n")
                append("Standing profiles in context: ")
                append(prompt.profiles.joinToString(", ") { it.name })
                append('.')
            }

            if (prompt.derived.isNotEmpty()) {
                append("\n\nLogic engine notes:")
                for (line in prompt.derived) append("\n• ").append(line)
            }

            append("\n\n(Placeholder response — a local ")
            append(spec.parameters)
            append(" model will replace this extractive summary with real reasoning over the same ")
            append("retrieved, cited context.)")
        }
    }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
}
