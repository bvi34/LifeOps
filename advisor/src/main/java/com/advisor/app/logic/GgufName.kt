package com.advisor.app.logic

import java.util.Locale

/**
 * What a GGUF's filename says about the model inside it.
 *
 * Advisor's model card is meant to be honest about what is running, and until now it wasn't able to
 * be: the spec it showed was a constant reading "Qwen3-4B", so *any* installed model was reported as
 * that one. That matters more than cosmetics here — the whole point of the card is telling the user
 * whether real weights or the placeholder are answering, and the size is the single thing that most
 * decides how fast an answer arrives.
 *
 * A filename is a weak source of truth, so this only claims what it can actually read, and falls back
 * to the filename itself rather than inventing a plausible-looking model.
 */
object GgufName {

    /** e.g. `qwen3-1.7b-q4_k_m.gguf` → `Qwen3-1.7B · 1.7B · Q4_K_M`. */
    fun specOf(fileName: String): ModelSpec {
        val stem = fileName.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF")
        val quant = QUANT.find(stem)?.value?.uppercase(Locale.US)
        val params = PARAMS.find(stem)?.groupValues?.get(1)?.uppercase(Locale.US)
        return ModelSpec(
            name = displayName(stem, quant),
            parameters = params ?: "unknown size",
            quantization = quant?.let { "$it / GGUF" } ?: "GGUF",
            isPlaceholder = false
        )
    }

    /**
     * The model's name: the stem with the quantization suffix dropped, tidied into the capitalisation
     * these models are usually written with. Anything it can't parse is shown as-is — a filename the
     * user recognises beats a guess they can't check.
     */
    private fun displayName(stem: String, quant: String?): String {
        // A name that is *only* a quantization tag has no model name to show, so show the filename
        // rather than a tidied-up version of a tag, which would read as a name it isn't.
        if (quant != null && stem.equals(quant, ignoreCase = true)) return stem

        var name = stem
        if (quant != null) {
            val at = name.lowercase(Locale.US).lastIndexOf(quant.lowercase(Locale.US))
            if (at > 0) name = name.substring(0, at).trimEnd('-', '_', '.', ' ')
        }
        if (name.isBlank()) return stem
        // "qwen3-1.7b-instruct" → "Qwen3-1.7B-Instruct": capitalise each dash-separated part, and
        // upper-case a bare parameter count so it reads "4B" rather than "4b".
        return name.split('-').joinToString("-") { part ->
            when {
                part.isEmpty() -> part
                PARAMS.matches(part) -> part.uppercase(Locale.US)
                else -> part.replaceFirstChar { it.uppercase() }
            }
        }
    }

    /** A parameter count as models name it: 4b, 1.7b, 0.6b, 70b. */
    private val PARAMS = Regex("""\b(\d+(?:\.\d+)?b)\b""", RegexOption.IGNORE_CASE)

    /** A GGUF quantization tag: q4_k_m, q8_0, iq4_xs, f16, bf16. */
    private val QUANT = Regex("""\b(?:iq\d+_\w+|q\d+_[\w_]+|bf16|f16|f32)\b""", RegexOption.IGNORE_CASE)
}
