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

    /**
     * Build the full ChatML prompt for [prompt]: the system instruction, the prior conversation as
     * real ChatML turns, then this question's augmentation as the final user turn.
     *
     * The prior turns must be real turns. Rendered as flat text inside one user message — `User: …`
     * / `Advisor: …` lines — nothing closes the assistant's previous message, so the model continues
     * it: each reply opens by restating the one before, that reply is persisted, and the next prompt
     * feeds it back, so answers grow without bound. An `<|im_end|>` after each assistant turn is what
     * says "this one is finished; write a new one".
     *
     * It also makes the prompt cheap to re-run. Everything up to the final user turn is byte-identical
     * from one question to the next, which is exactly the prefix the native backend keeps in its KV
     * cache instead of re-prefilling — so the volatile per-question material (retrieved CONTEXT, the
     * question) belongs last, where it is here.
     */
    fun forPrompt(prompt: AdvisorPrompt, enableThinking: Boolean = false): String = buildString {
        val system = prompt.system.trim()
        if (system.isNotBlank()) {
            append(IM_START).append("system\n").append(system).append(IM_END).append('\n')
        }
        for (turn in prompt.conversation) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            append(IM_START).append(if (turn.fromUser) "user\n" else "assistant\n")
            append(text).append(IM_END).append('\n')
        }
        append(IM_START).append("user\n").append(userContent(prompt)).append(IM_END).append('\n')
        append(IM_START).append("assistant\n")
        if (!enableThinking) append("<think>\n\n</think>\n\n")
    }

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

    /**
     * The final user turn: everything the assembler rendered, minus the system preamble, minus the
     * conversation (emitted as real turns by [forPrompt]), minus the trailing `ANSWER:` cue — the
     * cue's job is done by the open assistant turn that follows.
     */
    /**
     * [cleanOutput] for a completion that is still arriving. Same rules, plus the two cases that only
     * exist mid-stream, where a construct has begun but hasn't finished:
     *
     * - an opened `<think>` with no `</think>` yet — its contents are reasoning, and showing them
     *   before the block closes would flash the model's scratchpad and then take it back;
     * - a trailing fragment that is the beginning of a marker — `<`, `<|im_`, `<thi` — which
     *   [cleanOutput] can only recognise once it is whole.
     *
     * Both are simply held back until the rest of them lands. The second is matched against the
     * markers themselves rather than by swallowing any trailing `<`, so prose that happens to contain
     * one ("under 5 < 6 items") still streams normally.
     */
    fun cleanPartial(raw: String): String {
        var text = cleanOutput(raw)
        // cleanOutput drops a *closed* think block, so one still present here was never closed.
        val openThink = text.indexOf("<think>")
        if (openThink >= 0) text = text.substring(0, openThink)
        val open = text.lastIndexOf('<')
        if (open >= 0 && isPartialMarker(text.substring(open))) text = text.substring(0, open)
        return text.trim()
    }

    /** Whether [tail] is the start of a marker that hasn't finished arriving. */
    private fun isPartialMarker(tail: String): Boolean =
        MARKER_STARTS.any { it.startsWith(tail) } || (tail.startsWith("<|") && !tail.contains("|>"))

    /** The markers whose opening bytes must not be shown: control tokens, and think tags. */
    private val MARKER_STARTS = listOf("<|", "<think>", "</think>")

    private fun userContent(prompt: AdvisorPrompt): String {
        val body = prompt.render(includeConversation = false).removePrefix(prompt.system).trimStart()
        return body.removeSuffix("ANSWER:").trimEnd()
    }

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
}
