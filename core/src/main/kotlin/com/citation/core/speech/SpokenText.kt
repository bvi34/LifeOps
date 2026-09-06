package com.citation.core.speech

/**
 * Assembles an [Utterance]'s spoken string while keeping every character's canonical origin.
 *
 * The same shape as the reader's own render builder: text arrives either as [real] — a slice of the
 * chapter, whose offsets must survive — or as [synthetic] — words that exist only in the ear, like
 * the comma between two table cells. Real characters extend the current [SpokenRun]; anything that
 * breaks the correspondence (a dropped footnote marker, a collapsed line break, a synthesized
 * separator) closes it and opens the next. The result is an exact mapping in a handful of runs.
 *
 * Normalization happens here rather than in the planner because it is the same for every kind of
 * unit: runs of whitespace become one space (a sentence wrapped across three source lines is one
 * spoken line), and characters that exist for typesetting rather than for speech — soft hyphens,
 * zero-width joiners, the byte-order mark — are dropped outright. A soft hyphen left in place makes
 * some engines say a word twice.
 */
internal class SpokenTextBuilder {

    private val sb = StringBuilder()
    private val runs = mutableListOf<SpokenRun>()
    private var runSpokenStart = -1
    private var runCanonicalStart = -1
    private var runLength = 0

    /** Whether nothing speakable has been written yet. */
    val isBlank: Boolean get() = sb.isBlank()

    /**
     * Append the chapter's own characters in `[from, to)`, normalized, skipping any part of
     * [exclude] — the ranges the planner wants heard by nobody, footnote references above all.
     */
    fun real(text: String, from: Int, to: Int, exclude: List<IntRange> = emptyList()) {
        var index = from.coerceAtLeast(0)
        val limit = to.coerceAtMost(text.length)
        while (index < limit) {
            val skipTo = exclude.firstOrNull { index in it }?.let { it.last + 1 }
            if (skipTo != null && skipTo > index) {
                closeRun()
                index = skipTo
                continue
            }
            val ch = text[index]
            when {
                ch.isIgnorable() -> closeRun()
                ch.isWhitespace() -> {
                    // One space for the whole run, mapped to its first character; nothing is
                    // emitted at all when the run would only pad an end.
                    if (sb.isNotEmpty() && sb.last() != ' ') append(' ', index)
                    else closeRun()
                }
                else -> append(ch, index)
            }
            index++
        }
    }

    /** Append words that are in no book — a separator, an announcement — owning no canonical text. */
    fun synthetic(value: String) {
        if (value.isEmpty()) return
        closeRun()
        sb.append(value)
    }

    /** The finished spoken string and its mapping, with any trailing space trimmed away. */
    fun build(): Pair<String, List<SpokenRun>> {
        closeRun()
        var length = sb.length
        while (length > 0 && sb[length - 1] == ' ') length--
        if (length != sb.length) {
            sb.setLength(length)
            trimRunsTo(length)
        }
        return sb.toString() to runs.toList()
    }

    private fun append(ch: Char, canonicalIndex: Int) {
        val contiguous = runLength > 0 &&
            runCanonicalStart + runLength == canonicalIndex &&
            runSpokenStart + runLength == sb.length
        if (!contiguous) {
            closeRun()
            runSpokenStart = sb.length
            runCanonicalStart = canonicalIndex
            runLength = 0
        }
        sb.append(ch)
        runLength++
    }

    private fun closeRun() {
        if (runLength > 0) runs += SpokenRun(runSpokenStart, runCanonicalStart, runLength)
        runLength = 0
        runSpokenStart = -1
        runCanonicalStart = -1
    }

    private fun trimRunsTo(length: Int) {
        while (runs.isNotEmpty()) {
            val last = runs.last()
            if (last.spokenStart >= length) {
                runs.removeAt(runs.size - 1)
            } else if (last.spokenStart + last.length > length) {
                runs[runs.size - 1] = last.copy(length = length - last.spokenStart)
                return
            } else {
                return
            }
        }
    }

    /**
     * Characters that exist for typesetting and not for speech: soft hyphen, zero-width space,
     * zero-width non-joiner and joiner, and the byte-order mark.
     */
    private fun Char.isIgnorable(): Boolean =
        this == '\u00AD' || this == '\u200B' || this == '\u200C' ||
            this == '\u200D' || this == '\uFEFF'
}
