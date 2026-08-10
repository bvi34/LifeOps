package com.advisor.app.logic

/**
 * Recognises a request to **rename the assistant** — "call yourself Ava", "can you go by Ava?", "your
 * name is Ava now" — and returns the new name, or null when the turn isn't a rename request.
 *
 * This is the naming counterpart to [WriteIntent]: a rename is a *command* to persist how the assistant
 * should refer to itself (in the persona profile that [SmallTalk.assistantNameFrom] reads), not a data
 * question. Crucially it fires even when the command is phrased as a question ("can you call yourself
 * Ava?") — which is exactly why [WriteIntent] (deliberately blind to anything ending in "?") misses it
 * and the turn used to fall through to retrieval and dump unrelated rows. The repository checks this
 * before the retrieval pipeline and, on a hit, saves the name and confirms.
 *
 * Deliberately conservative: it only matches explicit naming phrasings and rejects captured "names"
 * that are really pronouns or filler ("call yourself something else"), so ordinary questions fall
 * through untouched. Pure and JVM-testable, like the rest of `logic/`.
 */
object AssistantNaming {

    /** The new assistant name requested by [question], or null when it isn't a rename request. */
    fun detect(question: String): String? {
        val q = question.trim()
        if (q.isEmpty()) return null
        for (pattern in PATTERNS) {
            val captured = pattern.find(q)?.groupValues?.get(1) ?: continue
            cleanName(captured)?.let { return it }
        }
        return null
    }

    /** Tidy a captured name and reject non-names (pronouns, filler) so only a real name is returned. */
    private fun cleanName(raw: String): String? {
        val name = raw.trim().trim('"', '\'', '’', '.', '!', ',').trim()
        if (name.length < 2) return null
        if (name.lowercase() in STOPNAMES) return null
        return name
    }

    // A single name token — a letter followed by letters/apostrophes/hyphens (e.g. "Ava", "D'Arcy").
    private const val NAME = """([A-Za-z][A-Za-z'’-]*)"""

    private val PATTERNS: List<Regex> = listOf(
        // "call yourself Ava" / "can you call yourself Ava (instead of Advisor)"
        Regex("""(?i)\bcall\s+yourself\s+$NAME"""),
        // "I'll call you Ava" / "can I call you Ava" / "let me call you Ava" / "I want to call you Ava"
        Regex(
            """(?i)\b(?:i(?:'|’)?ll|i\s+will|let\s+me|i\s+want\s+to|i\s+wanna|can\s+i|""" +
                """i\s+am\s+going\s+to|i(?:'|’)?m\s+going\s+to)\s+call\s+you\s+$NAME"""
        ),
        // "go by Ava" / "you should go by Ava" / "go by the name Ava"
        Regex("""(?i)\bgo\s+by\s+(?:the\s+name\s+)?$NAME"""),
        // "rename yourself to Ava" / "rename yourself as Ava"
        Regex("""(?i)\brename\s+yourself\s+(?:to|as)\s+$NAME"""),
        // "name yourself Ava" / "I'll name you Ava"
        Regex("""(?i)\bname\s+(?:yourself|you)\s+$NAME"""),
        // "your name is Ava" / "your name should be Ava" / "your name's Ava"
        Regex(
            """(?i)\byour\s+name\s+(?:is|should\s+be|will\s+be|shall\s+be|can\s+be|(?:'|’)s)\s+$NAME"""
        )
    )

    // Tokens that follow a naming verb but are not a name — reject these so no bogus rename fires. Two
    // groups: pronouns/filler ("call yourself something") and common compliments/adjectives that follow
    // "your name is …" as praise rather than a new name ("your name is nice").
    private val STOPNAMES = setOf(
        "yourself", "you", "me", "it", "that", "this", "something", "anything", "the", "a", "an",
        "your", "my", "please", "instead", "now", "advisor", "again", "different", "some", "any",
        "what", "who", "how", "by", "to", "as", "name", "names", "named", "another", "new", "same",
        "nice", "cool", "great", "good", "awesome", "lovely", "weird", "funny", "strange", "fine",
        "okay", "cute", "silly", "better", "best", "perfect", "wonderful", "pretty", "beautiful"
    )
}
