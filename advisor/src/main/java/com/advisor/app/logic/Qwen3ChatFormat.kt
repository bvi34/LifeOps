package com.advisor.app.logic

/**
 * Qwen3's ChatML prompt format, and the inverse clean-up of its completions. Pure and tiny so the
 * exact bytes fed to the GGUF model — and the way the raw output is turned back into a user-facing
 * answer — are stable and JVM-testable, independent of any native backend.
 *
 * Qwen3 is a hybrid *thinking* model: left to itself it emits a `<think>…</think>` block before the
 * answer. Advisor is a grounded, cite-your-sources assistant, so thinking is disabled by pre-filling
 * an empty think block (the same trick the official chat template uses for `enable_thinking=false`),
 * and any think block that still slips through is stripped from the output.
 */
object Qwen3ChatFormat {

    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"

    /** Build the full ChatML prompt for [prompt]. Thinking is disabled by default. */
    fun forPrompt(prompt: AdvisorPrompt, enableThinking: Boolean = false): String =
        build(prompt.system.trim(), userContent(prompt), enableThinking)

    /** Build a ChatML prompt from a [system] instruction and a [user] turn. */
    fun build(system: String, user: String, enableThinking: Boolean = false): String = buildString {
        if (system.isNotBlank()) {
            append(IM_START).append("system\n").append(system.trim()).append(IM_END).append('\n')
        }
        append(IM_START).append("user\n").append(user.trim()).append(IM_END).append('\n')
        append(IM_START).append("assistant\n")
        // Force non-thinking: pre-seed an empty reasoning block so the model answers straight away.
        if (!enableThinking) append("<think>\n\n</think>\n\n")
    }

    /**
     * Turn a raw completion into a clean answer: drop any `<think>…</think>` reasoning, cut anything
     * from the first turn-end / control token onward, and remove stray ChatML markers.
     */
    fun cleanOutput(raw: String): String {
        var text = THINK_BLOCK.replace(raw, "")
        // A think block the model opened but never closed (or that our seed left dangling).
        val closedThink = text.lastIndexOf("</think>")
        if (closedThink >= 0) text = text.substring(closedThink + "</think>".length)
        // Stop at the first end-of-turn / special token.
        for (marker in listOf(IM_END, IM_START, "<|endoftext|>")) {
            val at = text.indexOf(marker)
            if (at >= 0) text = text.substring(0, at)
        }
        return text.trim()
    }

    /** The user turn: everything the assembler rendered, minus the system preamble and trailing cue. */
    private fun userContent(prompt: AdvisorPrompt): String {
        val body = prompt.render().removePrefix(prompt.system).trimStart()
        return body.removeSuffix("ANSWER:").trimEnd()
    }

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
}
