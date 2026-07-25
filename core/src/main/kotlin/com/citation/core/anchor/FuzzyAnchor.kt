package com.citation.core.anchor

/**
 * Re-resolves a [TextAnchor.Flowing] against (possibly edited) chapter text by **quote + fuzzy
 * match**, not by brittle offsets.
 *
 * The problem this solves: a note is captured against chapter text as it read *then*. Later the
 * chapter is re-fetched and the author has fixed a typo, inserted a sentence, or the export shifted
 * every character position. A note that trusted `approxStart` would now point at the wrong words —
 * or off the end. So resolution works like a human re-finding a highlight: look for the exact quote
 * first (near where it used to be, disambiguated by the surrounding text), and if the passage was
 * edited, fall back to the *closest* run of text by similarity, accepting it only when it is close
 * enough to be confidently the same passage.
 *
 * The result is graded so the UI can distinguish a clean rebinding from a shaky one, and an
 * outright miss (the passage was deleted) degrades to an orphan rather than a wrong jump.
 */
object FuzzyAnchor {

    /** How an anchor resolved against current text. */
    enum class Confidence {
        /** Exact quote found (optionally proximity/context-disambiguated). Reliable jump. */
        EXACT,
        /** Quote not exact but a close passage matched above threshold. Probable, flag lightly. */
        FUZZY,
        /** No passage close enough. The note is intact but orphaned — don't jump anywhere. */
        NONE
    }

    /**
     * @property start resolved character offset in the searched text (−1 when [confidence] is NONE).
     * @property end exclusive end offset (−1 when NONE).
     * @property score similarity in 0.0..1.0 (1.0 for an exact hit).
     */
    data class Resolution(
        val confidence: Confidence,
        val start: Int,
        val end: Int,
        val score: Double
    ) {
        val matchedRange: IntRange? get() = if (confidence == Confidence.NONE) null else start until end
    }

    private const val FUZZY_THRESHOLD = 0.72

    /**
     * Resolve [anchor]'s quote within [text] (the current text of the anchor's chapter).
     *
     * @param minFuzzyScore similarity floor for accepting a fuzzy (non-exact) match.
     */
    fun resolve(anchor: TextAnchor.Flowing, text: String, minFuzzyScore: Double = FUZZY_THRESHOLD): Resolution {
        val quote = anchor.quote
        if (quote.isEmpty() || text.isEmpty()) return miss()

        // 1) Exact matches — pick the one closest to the capture-time hint, disambiguated by context.
        val exacts = allIndicesOf(text, quote)
        if (exacts.isNotEmpty()) {
            val best = exacts.minByOrNull { idx ->
                contextPenalty(text, idx, quote.length, anchor) +
                    Math.abs(idx - anchor.approxStart).toLong()
            }!!
            return Resolution(Confidence.EXACT, best, best + quote.length, 1.0)
        }

        // 2) Fuzzy — slide a quote-sized window and keep the best-scoring passage above the floor.
        val fuzzy = bestFuzzyWindow(text, quote)
        return if (fuzzy != null && fuzzy.score >= minFuzzyScore) {
            Resolution(Confidence.FUZZY, fuzzy.start, fuzzy.end, fuzzy.score)
        } else {
            miss()
        }
    }

    private fun miss() = Resolution(Confidence.NONE, -1, -1, 0.0)

    /** Prefer a candidate whose surrounding prefix/suffix still matches the captured context. */
    private fun contextPenalty(text: String, idx: Int, len: Int, anchor: TextAnchor.Flowing): Long {
        var penalty = 0L
        if (anchor.prefix.isNotEmpty()) {
            val before = text.substring(maxOf(0, idx - anchor.prefix.length), idx)
            if (!before.endsWith(anchor.prefix)) penalty += CONTEXT_MISS
        }
        if (anchor.suffix.isNotEmpty()) {
            val afterEnd = minOf(text.length, idx + len + anchor.suffix.length)
            val after = text.substring(idx + len, afterEnd)
            if (!after.startsWith(anchor.suffix)) penalty += CONTEXT_MISS
        }
        return penalty
    }

    private data class Window(val start: Int, val end: Int, val score: Double)

    /**
     * Scan word-boundary-aligned windows of roughly the quote's length and return the best by
     * similarity. Aligning candidate starts to word boundaries keeps this near-linear instead of
     * comparing at every character offset.
     */
    private fun bestFuzzyWindow(text: String, quote: String): Window? {
        val qNorm = normalize(quote)
        if (qNorm.isEmpty()) return null
        val starts = wordBoundaryStarts(text)
        val windowLen = quote.length
        var best: Window? = null
        for (s in starts) {
            val e = minOf(text.length, s + windowLen)
            val candidate = text.substring(s, e)
            val score = similarity(qNorm, normalize(candidate))
            if (best == null || score > best.score) best = Window(s, e, score)
            if (score == 1.0) break
        }
        return best
    }

    private fun wordBoundaryStarts(text: String): List<Int> {
        val starts = ArrayList<Int>()
        var prevSpace = true
        for (i in text.indices) {
            val isSpace = text[i].isWhitespace()
            if (prevSpace && !isSpace) starts.add(i)
            prevSpace = isSpace
        }
        if (starts.isEmpty()) starts.add(0)
        return starts
    }

    private fun allIndicesOf(text: String, sub: String): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(sub, from)
            if (i < 0) break
            out.add(i)
            from = i + 1
        }
        return out
    }

    /** Whitespace-collapsed, lower-cased form so edits to spacing/case don't sink a good match. */
    private fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Similarity in 0.0..1.0 from a bounded Levenshtein distance over the normalised strings. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    private const val CONTEXT_MISS = 1_000_000L
}
