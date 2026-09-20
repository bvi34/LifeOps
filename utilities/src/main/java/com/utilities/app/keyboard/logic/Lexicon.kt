package com.utilities.app.keyboard.logic

/**
 * The words this keyboard has learned, and the only thing it knows about how anybody types.
 *
 * ## The whole point of the app, in one class
 *
 * A keyboard that suggests words has to remember words, and remembering what somebody typed is
 * exactly the capability the two keyboards that ship on most phones use to send it away. So the
 * shape of this one is the argument:
 *
 *  - **it is a word list with counts, and nothing else.** No sequence, no context, no n-gram, no
 *    "who you were typing to". `hello` was typed eleven times. That is the entire record, and it is
 *    worth noticing how much less it is than what an ordinary predictive keyboard keeps;
 *  - **it never leaves.** The module it lives in has no network permission, so there is nothing to
 *    check and no switch to get wrong — see the manifest;
 *  - **it is refusable by the thing being typed into.** A password field, a field marked
 *    `noSuggestions`, or an editor asking for incognito typing (`IME_FLAG_NO_PERSONALIZED_LEARNING`)
 *    is never learned from. That check is at the call site in the service, because it is a property
 *    of the editor rather than of the list;
 *  - **it is legible.** The stored form is one `word<tab>count` per line, so anybody who wants to
 *    know what their keyboard has on them can read it, and the backup carries a text file rather
 *    than a binary somebody has to take on trust.
 *
 * Immutable, and [learn] returns a new one: a keyboard learns on a background write and suggests on
 * the main thread, and a list that could be mutated from under a lookup is a keyboard that
 * occasionally crashes mid-word.
 */
class Lexicon private constructor(private val counts: Map<String, Int>) {

    val size: Int get() = counts.size

    /** How often [word] has been typed, or 0. */
    fun count(word: String): Int = counts[word.lowercase()] ?: 0

    /**
     * Remember that [word] was typed.
     *
     * Returns the same lexicon, unchanged, when the word is not worth keeping — too short, not
     * alphabetic, or absurdly long. Short words are already fast to type and would crowd out the
     * long ones that suggestions actually save time on; anything with a digit or a symbol in it is
     * far more likely to be a password, an order number or a licence key than a word, and this list
     * is the one place in the app where guessing wrong means keeping something private.
     */
    fun learn(word: String): Lexicon {
        val clean = normalize(word) ?: return this
        val next = HashMap(counts)
        next[clean] = (next[clean] ?: 0) + 1
        return Lexicon(evictIfFull(next))
    }

    /** Forget one word — the row's swipe on the settings screen, and the reason it can be read. */
    fun forget(word: String): Lexicon {
        val clean = word.lowercase()
        if (!counts.containsKey(clean)) return this
        return Lexicon(counts - clean)
    }

    /**
     * Up to [limit] completions for [prefix], most-typed first.
     *
     * The prefix itself is never offered back: somebody who has typed `the` does not need to be
     * told they could type `the`. Ties break alphabetically so the strip does not shuffle between
     * two equally-typed words on consecutive keystrokes, which is the kind of flicker that makes a
     * suggestion strip feel untrustworthy.
     */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        return counts.asSequence()
            .filter { (word, _) -> word.length > p.length && word.startsWith(p) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
            .toList()
    }

    /** Every word, most-typed first — what the settings screen lists. */
    fun words(): List<Pair<String, Int>> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** One `word<tab>count` per line. See the class note for why the stored form is readable. */
    fun serialize(): String = words().joinToString("\n") { (word, count) -> "$word\t$count" }

    /**
     * Drop the least-typed words once the list is over [MAX_WORDS].
     *
     * A cap rather than unbounded growth, and a low one: the list is read into memory on every
     * process start and scanned on every keystroke, and past a few thousand words the suggestions
     * stop improving while the file keeps growing. It is also the smaller record, which is the
     * preference this class resolves ties by.
     */
    private fun evictIfFull(words: MutableMap<String, Int>): Map<String, Int> {
        if (words.size <= MAX_WORDS) return words
        return words.entries
            .sortedWith(compareByDescending<MutableMap.MutableEntry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_WORDS)
            .associate { it.key to it.value }
    }

    companion object {

        const val MAX_WORDS = 4000
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 32

        val EMPTY = Lexicon(emptyMap())

        fun of(counts: Map<String, Int>): Lexicon = Lexicon(counts.filterKeys { normalize(it) != null })

        /** Read back [serialize]. A line that does not parse is skipped rather than fatal. */
        fun parse(text: String): Lexicon {
            val counts = HashMap<String, Int>()
            text.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) return@forEach
                val word = normalize(line.substring(0, tab)) ?: return@forEach
                val count = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEach
                if (count > 0) counts[word] = maxOf(counts[word] ?: 0, count)
            }
            return Lexicon(counts)
        }

        /**
         * A word as it is stored, or null when it is not one worth storing.
         *
         * Letters and an interior apostrophe, which is what makes `don't` a word and `don''t`,
         * `A1`, `x` and a forty-character token not.
         */
        fun normalize(raw: String): String? {
            val word = raw.trim().trim('\'', '’').lowercase()
            if (word.length < MIN_LENGTH || word.length > MAX_LENGTH) return null
            if (!word.all { it.isLetter() || it == '\'' || it == '’' }) return null
            if (word.none { it.isLetter() }) return null
            return word
        }

        /**
         * The word the cursor is sitting in the middle of, given the text before it.
         *
         * Used for suggestions, so it deliberately takes *anything* that could be a partial word,
         * rather than the stricter [normalize] used for learning: somebody two letters into a name
         * should be offered completions even though two letters would never be stored.
         */
        fun wordBeforeCursor(textBefore: String): String =
            textBefore.takeLastWhile { it.isLetter() || it == '\'' || it == '’' }
    }
}
