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
        "my name", "about me", "know me", "do you know of me", "about myself", "tell me about me", "who i'm"
    )

    private val MYSELF = Regex("\\bmyself\\b")

    /** True when [question] is asking about the user's own identity. */
    fun isAboutUser(question: String): Boolean {
        val q = question.lowercase()
        return PHRASES.any { q.contains(it) } || MYSELF.containsMatchIn(q)
    }

    /**
     * [isAboutUser], plus questions that name the user in the third person — "what do you know about
     * Brenden Villaruel?" is the same question as "what do you know about me?", and answering it from
     * retrieved app rows (a temperature log, a pantry item) instead of what is held about them is
     * exactly the wrong answer. Only when the name follows "about", "who is" or "know", so a question
     * that merely mentions them ("what tasks does Brenden have?") still goes to retrieval.
     */
    fun isAboutUser(question: String, userName: String): Boolean {
        if (isAboutUser(question)) return true
        val name = userName.trim().lowercase()
        val first = name.substringBefore(' ')
        if (first.length < 2) return false
        val names = listOf(name, first).distinct().joinToString("|") { Regex.escape(it) }
        return Regex("\\b(?:about|who is|who's|whos|know)\\s+(?:of\\s+)?(?:$names)\\b")
            .containsMatchIn(question.lowercase())
    }
}
