package com.advisor.app.logic

/**
 * Which of the prior turns go into the prompt.
 *
 * The obvious rule — "the last N messages" — is the expensive one. The native backend keeps whatever
 * the new prompt shares with the previous one and prefills only the rest, and Advisor's prompt is
 * `system + history + this question`, so the history is *exactly* the part that could be reused. A
 * window that slides by one exchange every turn moves every message in it, so the shared prefix
 * collapses to the system preamble and the entire history is re-prefilled — every turn, for the whole
 * conversation.
 *
 * So the window is anchored instead of sliding: its start only moves in [STRIDE]-message jumps, and
 * between jumps it *grows*, which is the case where reuse is perfect. The cost of a longer history is
 * paid once every few turns rather than continuously.
 *
 * The rule is a pure function of the conversation's length, so nothing has to be remembered between
 * questions for two consecutive prompts to agree on where the history starts.
 */
object ConversationWindow {

    /**
     * Messages discarded at a time when the window does have to move. Four is two exchanges, and a
     * multiple of two — the window always starts on a user message, never mid-exchange on a reply
     * whose question has been dropped.
     */
    const val STRIDE = 4

    /** The most messages worth carrying before the character budget has its say. */
    const val MAX_MESSAGES = 16

    /**
     * The index, in the whole conversation, of the first message to include when it holds [total]
     * messages. A multiple of [STRIDE], so it holds still while the conversation grows into it.
     */
    fun startIndex(total: Int, maxMessages: Int = MAX_MESSAGES): Int {
        if (total <= maxMessages) return 0
        val over = total - maxMessages
        return STRIDE * ((over + STRIDE - 1) / STRIDE)
    }

    /**
     * [turns] (oldest first) reduced to what fits in [budgetChars], dropping whole strides off the
     * front. Stride-aligned like [startIndex] for the same reason: a window that gave back one message
     * at a time would shift on every turn and reuse nothing.
     */
    fun fit(turns: List<ConversationTurn>, budgetChars: Int): List<ConversationTurn> {
        if (budgetChars <= 0 || turns.isEmpty()) return emptyList()
        var from = 0
        while (from < turns.size && cost(turns, from) > budgetChars) from += STRIDE
        return if (from >= turns.size) emptyList() else turns.subList(from, turns.size)
    }

    /** What [turns] from [from] onward cost, including each turn's chat-template wrapper. */
    private fun cost(turns: List<ConversationTurn>, from: Int): Int {
        var total = 0
        for (i in from until turns.size) total += turns[i].text.length + TURN_OVERHEAD
        return total
    }

    /** Roughly `<|im_start|>assistant\n` … `<|im_end|>\n` around every turn. */
    private const val TURN_OVERHEAD = 30
}
