package com.utilities.app.keyboard.logic

/**
 * Everything the keyboard knows a word from: what this household types, and English.
 *
 * Two lists with two different standings, and the ordering between them is the point of the class:
 *
 *  - **[typed] comes first, always.** A word somebody has typed even once outranks the commonest
 *    word in the dictionary. That is what stops a keyboard fighting with the people using it —
 *    surnames, street names, the name of a cat, the jargon of whatever they do for a living. It is
 *    also the only half of this that is personal, and it stays as refusable, readable and deletable
 *    as it was: see [Lexicon] and the keyboard's settings screen;
 *  - **[english] is the floor.** A brand-new keyboard that has learned nothing still knows that
 *    `teh` is not a word and `the` is, which is the difference between autocorrect being useful on
 *    day one and being useful in a month.
 *
 * The numbers are on one scale so that [Corrections] can do arithmetic across both lists without
 * caring which one an answer came from. They are small and blunt on purpose: the decision they feed
 * is "is this worth overruling somebody's thumbs about", and that decision does not get better by
 * being computed to three decimal places.
 */
class Vocabulary(
    val typed: Lexicon = Lexicon.EMPTY,
    val english: WordBook = WordBook.EMPTY
) {

    /** Whether this is a word at all — the first question [Corrections] asks, and usually the last. */
    fun knows(word: String): Boolean = weight(word) > 0

    /**
     * How good a word this is to correct towards. 0 means it is not a word.
     *
     * A typed word is worth more than any dictionary word, and worth a little more again for having
     * been typed often — capped, because the difference between a word typed fifty times and one
     * typed five hundred times is not a difference worth having.
     */
    fun weight(word: String): Int {
        val clean = normalize(word)
        if (clean.isEmpty()) return 0
        val count = typed.count(clean)
        if (count > 0) return TYPED + minOf(count, TYPED_CAP)
        return when (english.tier(clean)) {
            1 -> COMMON
            2 -> ORDINARY
            3 -> RARE
            else -> 0
        }
    }

    /**
     * Up to [limit] completions of [prefix] for the strip: this household's words, then English.
     *
     * Not interleaved by any score. A word somebody has actually typed is a better guess than a
     * commoner word they have not, and mixing the two by weight would put `according` in front of a
     * colleague's name every time — which is the behaviour that teaches people to stop looking at
     * the strip.
     */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val clean = normalize(prefix)
        val mine = typed.suggest(clean, limit)
        if (mine.size >= limit) return mine
        val rest = english.suggest(clean, limit + mine.size).filterNot { it in mine }
        return (mine + rest).take(limit)
    }

    companion object {

        /** A word this household has typed, plus one point per time typed up to [TYPED_CAP]. */
        const val TYPED = 62
        const val TYPED_CAP = 18

        /**
         * The three tiers of the dictionary — see [WordBook].
         *
         * [RARE] is deliberately below what [Corrections] will accept. A word in the long tail is
         * one the keyboard recognises, and therefore one it will never change — but it is not a
         * word it will ever change anything *into*. Nobody has been well served by a keyboard that
         * turned what they typed into a word they have never used: `alot` is a mistake, and `alto`
         * is not an improvement on it.
         */
        const val COMMON = 60
        const val ORDINARY = 52
        const val RARE = 38

        /**
         * A word as both lists hold it: lower case, and the typographer's apostrophe folded onto the
         * one on the keyboard. `don’t` typed and `don't` stored are the same word, and a dictionary
         * that disagreed would correct one into the other for ever.
         */
        fun normalize(word: String): String = word.lowercase().replace('’', '\'')
    }
}
