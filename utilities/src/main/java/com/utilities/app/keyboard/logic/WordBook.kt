package com.utilities.app.keyboard.logic

/**
 * The general dictionary: the English this keyboard knows without having been taught it.
 *
 * ## What it is, and what it is emphatically not
 *
 * It is a list of English words shipped inside the app, generated from SCOWL by
 * `utilities/tools/make-wordbook.sh` and never written to. It is the other half of the answer to
 * "how does a keyboard correct a typo" — [Lexicon] is what *this household* types, this is what
 * everybody types — and the distinction matters more here than it would in another app: the learned
 * list is personal and is therefore readable, deletable and refusable, while this one says nothing
 * about anybody and so needs none of that. A word being in here is not evidence that anyone typed
 * it.
 *
 * ## Three tiers, not a frequency
 *
 * Each word carries how common it is — 1 for the four thousand or so that make up most of what
 * anybody writes, 2 for the next several thousand, 3 for the ordinary rest. Three coarse tiers
 * rather than a count per word, because the tiers are what a permissively-licensed word list
 * actually gives you and because the decision they feed is itself coarse: how far a typo is allowed
 * to travel before the correction is refused. See [Corrections].
 *
 * ## Why it is stored as one long string
 *
 * Fifty thousand `String` objects is a couple of megabytes of heap, held for the life of a process
 * that exists to draw thirty rectangles over somebody else's app. The same words in one string with
 * an index of where each begins is a few hundred kilobytes and is *faster* to search, because a
 * binary search over it touches a handful of characters and allocates nothing at all. Words come
 * back out as strings only when there is something to show.
 *
 * The file is sorted by character code and this class relies on that. [parse] checks it and sorts
 * a file that is not, because the failure it would otherwise cause is a silent one — see there.
 */
class WordBook private constructor(
    /** Every word, in order, each followed by a newline. */
    private val blob: String,
    /** Where word `i` starts in [blob]. One longer than the number of words; the last is the end. */
    private val starts: IntArray,
    /** How common word `i` is: 1, 2 or 3. */
    private val tiers: ByteArray
) {

    val size: Int get() = tiers.size

    /** Whether this is a word of English at all. */
    fun contains(word: String): Boolean = indexOf(word) >= 0

    /** How common [word] is — 1 the commonest, 3 the long tail — or 0 when it is not in here. */
    fun tier(word: String): Int {
        val index = indexOf(word)
        return if (index < 0) 0 else tiers[index].toInt()
    }

    /**
     * Up to [limit] words beginning with [prefix], commonest first.
     *
     * The prefix itself is never offered back — somebody who has typed `the` does not need to be
     * told they could type `the` — and ties go to the shorter word and then to the alphabet, so the
     * strip does not reshuffle itself between two keystrokes that both match.
     */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty() || limit <= 0 || size == 0) return emptyList()
        val found = ArrayList<Int>(limit)
        var index = lowerBound(prefix)
        while (index < size && startsWith(index, prefix)) {
            if (length(index) > prefix.length) insert(found, index, limit)
            index++
        }
        return found.map { word(it) }
    }

    /** Keep the best [limit] seen so far, in order. Small enough that a linear insert is the cheap way. */
    private fun insert(found: ArrayList<Int>, candidate: Int, limit: Int) {
        var at = found.size
        while (at > 0 && better(candidate, found[at - 1])) at--
        if (at >= limit) return
        found.add(at, candidate)
        if (found.size > limit) found.removeAt(found.size - 1)
    }

    /** Commoner wins; then shorter, which is the likelier thing to have been reaching for. */
    private fun better(a: Int, b: Int): Boolean {
        if (tiers[a] != tiers[b]) return tiers[a] < tiers[b]
        return length(a) < length(b)
    }

    private fun word(index: Int): String = blob.substring(starts[index], starts[index + 1] - 1)

    private fun length(index: Int): Int = starts[index + 1] - starts[index] - 1

    /** Where [query] is, or -1. The one place the sortedness of the file is cashed in. */
    private fun indexOf(query: String): Int {
        if (query.isEmpty()) return -1
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(middle, query)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    /** The first word that does not sort before [prefix]. */
    private fun lowerBound(prefix: String): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compare(middle, prefix) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    /** Word [index] against [query], the way [String.compareTo] would, without building the word. */
    private fun compare(index: Int, query: String): Int {
        var here = starts[index]
        val end = starts[index + 1] - 1
        var there = 0
        while (here < end && there < query.length) {
            val difference = blob[here].code - query[there].code
            if (difference != 0) return difference
            here++
            there++
        }
        return (end - starts[index]) - query.length
    }

    private fun startsWith(index: Int, prefix: String): Boolean {
        if (length(index) < prefix.length) return false
        val start = starts[index]
        for (offset in prefix.indices) {
            if (blob[start + offset] != prefix[offset]) return false
        }
        return true
    }

    companion object {

        /** A keyboard with no dictionary yet: the file is read off the main thread, so this is what it has until then. */
        val EMPTY = WordBook("", intArrayOf(0), ByteArray(0))

        /**
         * Read the shipped file.
         *
         * `# …` is a comment — the provenance and the copyright notice the licence asks to travel
         * with the words — and every other line is a tier digit followed by the word. A line that is
         * neither is skipped rather than fatal: a keyboard that will not come up because one line of
         * a dictionary is malformed is a worse outcome than a keyboard missing one word.
         *
         * The generator writes the words in order and this checks that they are, because the
         * alternative failure is the silent kind: a binary search over a list that is not sorted
         * does not throw, it reports that half of English is not English, and the only symptom is a
         * keyboard that mysteriously stops correcting. Checking costs one comparison per line.
         * Sorting, in the case where it is needed, costs a moment of a background thread once — and
         * is worth it, because a dictionary in the wrong order is not a thing anybody would think to
         * look for.
         */
        fun parse(lines: Sequence<String>): WordBook {
            val words = ArrayList<String>()
            val tiers = ArrayList<Byte>()
            var ordered = true
            lines.forEach { line ->
                if (line.isEmpty() || line[0] == '#') return@forEach
                val tier = line[0] - '0'
                if (tier < 1 || tier > 3 || line.length < 2) return@forEach
                val word = line.substring(1)
                if (ordered && words.isNotEmpty() && words.last() >= word) ordered = false
                words.add(word)
                tiers.add(tier.toByte())
            }
            if (words.isEmpty()) return EMPTY

            val order = if (ordered) words.indices.toList() else words.indices.sortedBy { words[it] }
            val blob = StringBuilder(words.sumOf { it.length + 1 })
            val starts = IntArray(order.size + 1)
            val ranks = ByteArray(order.size)
            order.forEachIndexed { position, index ->
                starts[position] = blob.length
                blob.append(words[index]).append('\n')
                ranks[position] = tiers[index]
            }
            starts[order.size] = blob.length
            return WordBook(blob.toString(), starts, ranks)
        }

        fun parse(text: String): WordBook = parse(text.lineSequence())
    }
}
