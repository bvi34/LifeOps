package com.citation.core.speech

/**
 * Cuts a run of prose into sentence-sized ranges — the unit a narrator actually works in.
 *
 * Why a sentence and not a paragraph: everything the reader can do while listening is expressed in
 * these boundaries. Skipping back ten seconds is really skipping back a sentence; resuming after a
 * phone call restarts at the top of one; the highlight following the voice moves between them; and
 * a neural voice has a bounded input length anyway, so something has to make the cut. Making it
 * *here*, once, means every engine gets the same units and the reader gets the same behaviour from
 * whichever one is installed.
 *
 * Why hand-rolled rather than `BreakIterator`: the platform's sentence iterator is locale data, not
 * a reading model. It has no opinion about "Mr." or "e.g." beyond what the locale ships, it differs
 * between the JVM and Android's ICU (so tests here would prove nothing about the device), and it
 * cannot be told that a 900-character sentence has to be broken *somewhere sensible* because the
 * voice cannot hold it. The rules below are few, explicit, and testable — the same reasoning the
 * codecs use for hand-rolling their JSON.
 *
 * Ranges returned are **canonical offsets into the chapter's text**, never substrings: nothing here
 * copies or edits a character, so the anchoring contract is untouched.
 */
object SentenceSplitter {

    /** Sentence-ending punctuation, Western and CJK. */
    private const val TERMINATORS = ".!?…。！？"

    /** Closers that belong to the sentence they follow: `He said "stop." Then he left.` */
    private const val CLOSERS = "\"'”’)]}»›"

    /**
     * Words whose trailing period ends an abbreviation rather than a sentence.
     *
     * Kept short on purpose. Every entry is a word that is *overwhelmingly* an abbreviation in
     * running prose, because the cost of a wrong entry is a sentence that never gets cut (and so
     * runs into the next) while the cost of a missing entry is only a slightly early pause. The
     * lowercase-follows rule below catches most of what is not listed.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "rev", "hon", "st", "sr", "jr", "capt", "col", "gen",
        "lt", "sgt", "messrs", "mme", "mlle",
        "e.g", "i.e", "cf", "viz", "etc", "vs", "al", "ibid", "op", "cit",
        "fig", "figs", "no", "nos", "vol", "vols", "ch", "chap", "pp", "ed", "eds", "trans",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sept", "sep", "oct", "nov", "dec",
        "mon", "tue", "tues", "wed", "thu", "thurs", "fri", "sat", "sun",
        "inc", "ltd", "co", "corp", "dept", "univ", "assn", "bros",
        "approx", "min", "max", "est", "a.m", "p.m", "u.s", "u.k"
    )

    /**
     * Split `[from, to)` of [text] into sentence ranges, each capped at [maxCharacters].
     *
     * Blank input yields nothing. Ranges are returned in order, cover every non-blank stretch of the
     * input, and never overlap. Leading and trailing whitespace is trimmed off each range so the
     * highlight lands on words rather than on the gap before them.
     */
    fun split(text: String, from: Int, to: Int, maxCharacters: Int = DEFAULT_MAX_CHARACTERS): List<IntRange> {
        val start = from.coerceAtLeast(0)
        val limit = to.coerceAtMost(text.length)
        if (start >= limit) return emptyList()

        val sentences = mutableListOf<IntRange>()
        var cut = start
        var index = start
        while (index < limit) {
            if (text[index] in TERMINATORS && isBoundary(text, index, limit)) {
                var end = index + 1
                while (end < limit && (text[end] in TERMINATORS || text[end] in CLOSERS)) end++
                trimmed(text, cut, end)?.let { sentences += it }
                cut = end
                index = end
            } else {
                index++
            }
        }
        trimmed(text, cut, limit)?.let { sentences += it }

        return sentences.flatMap { cap(text, it, maxCharacters) }
    }

    /**
     * Whether the terminator at [index] really ends a sentence.
     *
     * `!` and `?` are taken at their word; the period carries all the ambiguity. It ends a sentence
     * only when what follows looks like a new one — whitespace, then something that starts prose —
     * and when what precedes it is not a decimal, an initial, or a known abbreviation.
     */
    private fun isBoundary(text: String, index: Int, limit: Int): Boolean {
        val ch = text[index]
        var after = index + 1
        while (after < limit && (text[after] in TERMINATORS || text[after] in CLOSERS)) after++

        // Mid-word punctuation is not a boundary: decimals, version numbers, urls, "U.S.A".
        if (after < limit && !text[after].isWhitespace()) return false

        var next = after
        while (next < limit && text[next].isWhitespace()) next++
        // A lowercase continuation means the pause was inside a sentence, not between two: this is
        // what quietly handles the abbreviations the list below does not name.
        if (next < limit && text[next].isLowerCase()) return false

        if (ch != '.') return true

        val word = wordBefore(text, index)
        if (word.isEmpty()) return true
        // A lone capital is an initial — "J. R. R. Tolkien" is one name, not three sentences.
        if (word.length == 1 && word[0].isUpperCase()) return false
        if (word.all { it.isDigit() }) return false
        return word.lowercase() !in ABBREVIATIONS
    }

    /** The word (letters, digits, and inner periods) immediately before [index]. */
    private fun wordBefore(text: String, index: Int): String {
        var start = index
        while (start > 0) {
            val ch = text[start - 1]
            if (ch.isLetterOrDigit() || ch == '.') start-- else break
        }
        return text.substring(start, index)
    }

    /**
     * Break a sentence that outruns [maxCharacters].
     *
     * Long sentences are real — Victorian prose, a legal clause, a run-on the splitter failed to
     * cut — and a voice that has to swallow one whole becomes unseekable and, on a neural engine,
     * often truncated. So the cut falls at the last clause boundary before the cap, then at the last
     * space, and only as a last resort mid-word. Punctuation stays with the half it followed.
     */
    private fun cap(text: String, range: IntRange, maxCharacters: Int): List<IntRange> {
        val max = maxCharacters.coerceAtLeast(MIN_MAX_CHARACTERS)
        if (range.last - range.first + 1 <= max) return listOf(range)

        val parts = mutableListOf<IntRange>()
        var from = range.first
        while (range.last - from + 1 > max) {
            val ceiling = from + max
            val cut = lastIndexOfAny(text, from + max / 2, ceiling, CLAUSE_BREAKS)?.plus(1)
                ?: lastWhitespace(text, from + max / 2, ceiling)
                ?: ceiling
            trimmed(text, from, cut)?.let { parts += it }
            from = cut
        }
        trimmed(text, from, range.last + 1)?.let { parts += it }
        return if (parts.isEmpty()) listOf(range) else parts
    }

    private fun lastIndexOfAny(text: String, from: Int, to: Int, chars: String): Int? {
        for (i in to - 1 downTo from) if (text[i] in chars) return i
        return null
    }

    private fun lastWhitespace(text: String, from: Int, to: Int): Int? {
        for (i in to - 1 downTo from) if (text[i].isWhitespace()) return i + 1
        return null
    }

    /** `[from, to)` with the whitespace trimmed off both ends, or `null` if nothing is left. */
    private fun trimmed(text: String, from: Int, to: Int): IntRange? {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) start until end else null
    }

    /** Clause punctuation, in the order a reader would break on. */
    private const val CLAUSE_BREAKS = ";:—–,"

    /** Long enough for a real sentence, short enough for every neural voice's input window. */
    const val DEFAULT_MAX_CHARACTERS = 280

    /** Below this a cap would cut ordinary sentences to pieces, so it is the floor. */
    const val MIN_MAX_CHARACTERS = 40
}
