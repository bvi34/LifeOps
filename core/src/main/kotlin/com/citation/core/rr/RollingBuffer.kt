package com.citation.core.rr

/**
 * The **rolling buffer** (fast lane): keep a small window of chapters cached *ahead* of where you
 * are reading, and refill it as you advance.
 *
 * The target is "~5 chapters / ~50k characters ahead": enough that turning the page never waits on
 * the network, but not so much that a non-favourite hoards storage. The window slides forward with
 * the read position, so [plan] is called on every advance and naturally requests only what's newly
 * fallen inside the look-ahead and isn't already cached.
 */
object RollingBuffer {

    const val DEFAULT_AHEAD_CHAPTERS = 5
    const val DEFAULT_AHEAD_CHARS = 50_000

    /**
     * Compute the chapters to prefetch, in reading order.
     *
     * The look-ahead window starts just after [currentOrdinal] and extends forward until it holds at
     * least [aheadChapters] chapters **and** (when [chapterCharSizes] are known) at least
     * [aheadChars] characters — whichever reaches further — bounded by [totalChapters]. When sizes
     * are unknown the char budget can't be measured, so the window is purely the chapter count. The
     * returned list is the window minus what's already in [cachedOrdinals].
     */
    fun plan(
        currentOrdinal: Int,
        cachedOrdinals: Set<Int>,
        totalChapters: Int,
        chapterCharSizes: Map<Int, Int> = emptyMap(),
        aheadChapters: Int = DEFAULT_AHEAD_CHAPTERS,
        aheadChars: Int = DEFAULT_AHEAD_CHARS
    ): List<Int> {
        if (totalChapters <= 0) return emptyList()
        val window = ArrayList<Int>()
        var chars = 0
        var ordinal = currentOrdinal + 1
        while (ordinal < totalChapters) {
            val haveEnoughChapters = window.size >= aheadChapters
            val haveEnoughChars = chapterCharSizes.isEmpty() || chars >= aheadChars
            if (haveEnoughChapters && haveEnoughChars) break
            window.add(ordinal)
            chars += chapterCharSizes[ordinal] ?: 0
            ordinal++
        }
        return window.filter { it !in cachedOrdinals }
    }
}
