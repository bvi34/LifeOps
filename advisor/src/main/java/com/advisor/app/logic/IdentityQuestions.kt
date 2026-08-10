package com.advisor.app.logic

/**
 * Recognises questions that are *about the user themselves* — "who am I", "what's my name", "about
 * me". These rarely share literal words with the stored facts (a "Name: Brenden Villaruel" line has
 * no "who"/"am"/"i" in it), so the purely lexical grounding in [C3AEngine] misses them and the
 * question falls through to a clarification even though the answer is right there in identity or the
 * user profile.
 *
 * Both engines consult this: [C3AEngine] to treat held identity/profile data as sufficient grounding
 * for such a question, and the placeholder engine to surface the actual facts rather than only naming
 * the profiles.
 */
internal object IdentityQuestions {

    // Phrases that mark a question as being about the user. Matched as substrings of the lowercased
    // question so trailing punctuation ("who am I?") and surrounding words don't defeat them.
    private val PHRASES = listOf(
        "who am i", "who i am", "what is my name", "what's my name", "whats my name",
        "my name", "about me", "about myself", "tell me about me", "who i'm"
    )

    private val MYSELF = Regex("\\bmyself\\b")

    /** True when [question] is asking about the user's own identity. */
    fun isAboutUser(question: String): Boolean {
        val q = question.lowercase()
        return PHRASES.any { q.contains(it) } || MYSELF.containsMatchIn(q)
    }
}
