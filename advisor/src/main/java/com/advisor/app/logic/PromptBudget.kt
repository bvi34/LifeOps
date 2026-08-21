package com.advisor.app.logic

/**
 * How much prompt there is room for, in the model's context window.
 *
 * Two different failures made this worth having. A prompt that overruns the context was truncated
 * *from the head* by the native backend — which is where the system instruction lives, so the model
 * lost its entire grounding contract exactly when the conversation was longest and it needed it most.
 * And every token of prompt is a token to prefill: at the few tens of tokens per second a 4B model
 * manages on a phone, a needlessly long prompt is measured in seconds of staring at the screen.
 *
 * The estimates here are deliberately pessimistic — they exist to keep a prompt *under* a hard limit,
 * so erring long is safe and erring short is not.
 */
object PromptBudget {

    /**
     * Characters per token, rounded down from the ~3.5–4 typical of English so the estimate runs
     * high. Exact tokenization lives in the native backend behind a model this layer cannot see, and
     * asking it would mean a JNI call per candidate window.
     */
    const val CHARS_PER_TOKEN = 3

    /**
     * Headroom left for the model's own reply, plus slack for the estimate being an estimate: the
     * chat template's control tokens, and the tokenizer disagreeing with a character count.
     */
    const val SAFETY_TOKENS = 128

    /** Tokens [text] is worth, rounded up. */
    fun estimate(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Characters of conversation history that fit alongside [promptWithoutConversation], given a
     * context window of [contextTokens] and a reply of up to [replyTokens]. Never negative: a prompt
     * that already fills the window simply leaves no room for history, and dropping history is the
     * right thing to drop — unlike the system instruction, it is the part the model can do without.
     */
    fun historyChars(
        promptWithoutConversation: String,
        contextTokens: Int,
        replyTokens: Int = GenerationParams().maxTokens
    ): Int {
        val spare = contextTokens - replyTokens - SAFETY_TOKENS - estimate(promptWithoutConversation)
        return if (spare <= 0) 0 else spare * CHARS_PER_TOKEN
    }
}
