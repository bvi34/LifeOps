package com.citation.core.reader

import com.citation.core.model.Book

/**
 * Where you are in a book, and how much of it is left — measured in **characters**, not chapters.
 *
 * The reader used to say "Chapter 3 / 40", which is a location, not progress: chapters are not the
 * same size, and in most books they are not even close. Three chapters into a book whose first three
 * are a foreword, a preface and a note on the text is not 7.5% read. Counting characters costs
 * nothing (the [Book] is already in memory) and is the only measure that stays honest across a
 * novel, a reference work and a web serial alike.
 *
 * Everything here is a pure function of the book and a position, so the same numbers can be shown in
 * the reader, in the library, and in a resume card without any of them re-deriving it.
 */
object ReadingProgress {

    /**
     * A position in a book, and what it means.
     *
     * @property charactersRead characters before the current offset, across the whole book.
     * @property charactersTotal characters in the whole book.
     * @property chapterFraction how far through the current chapter, 0..1.
     */
    data class Position(
        val chapterOrdinal: Int,
        val charOffset: Int,
        val charactersRead: Int,
        val charactersTotal: Int,
        val chapterFraction: Float
    ) {
        /** How far through the book, 0..1. */
        val fraction: Float
            get() = if (charactersTotal <= 0) 0f else (charactersRead.toFloat() / charactersTotal).coerceIn(0f, 1f)

        /** Whole percent, for display. Never rounds up to 100 before the end. */
        val percent: Int
            get() {
                val raw = (fraction * 100).toInt()
                return if (raw >= 100 && charactersRead < charactersTotal) 99 else raw.coerceIn(0, 100)
            }

        val charactersLeft: Int get() = (charactersTotal - charactersRead).coerceAtLeast(0)
    }

    /** Cumulative characters before each chapter, plus the total. Cheap to keep, cheap to recompute. */
    fun cumulative(book: Book): IntArray {
        val out = IntArray(book.chapters.size + 1)
        book.chapters.forEachIndexed { i, chapter -> out[i + 1] = out[i] + chapter.text.length }
        return out
    }

    /** Where [chapterOrdinal] + [charOffset] sits in [book]. */
    fun at(book: Book, chapterOrdinal: Int, charOffset: Int): Position {
        val cumulative = cumulative(book)
        val total = cumulative.last()
        val ordinal = chapterOrdinal.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
        val chapterLength = book.chapters.getOrNull(ordinal)?.text?.length ?: 0
        val offset = charOffset.coerceIn(0, chapterLength)
        return Position(
            chapterOrdinal = ordinal,
            charOffset = offset,
            charactersRead = (cumulative.getOrElse(ordinal) { 0 } + offset).coerceIn(0, total),
            charactersTotal = total,
            chapterFraction = if (chapterLength <= 0) 0f else (offset.toFloat() / chapterLength).coerceIn(0f, 1f)
        )
    }

    /** Characters remaining in the current chapter — what "left in this chapter" is measured from. */
    fun charactersLeftInChapter(book: Book, chapterOrdinal: Int, charOffset: Int): Int {
        val length = book.chapters.getOrNull(chapterOrdinal)?.text?.length ?: return 0
        return (length - charOffset).coerceAtLeast(0)
    }
}

/**
 * How fast this reader reads, learned from their own reading.
 *
 * Every reader app that estimates "time left" either asks you to pick a words-per-minute or assumes
 * one. Citation already measures **engaged** time honestly — [ReadingMeter] voids the stretch where
 * you left the book open and walked away — so the pace can simply be observed: characters actually
 * covered, over minutes actually spent covering them.
 *
 * Two properties keep the estimate from lying:
 *
 *  - It is **smoothed**, so one page read in a hurry does not swing the estimate.
 *  - It reports whether it is [confident] yet, and callers show nothing rather than a guess. A
 *    made-up "4 hours left" on the first page is worse than no number at all, because a reader has
 *    no way to tell that it was invented.
 */
data class ReadingPace(
    /** Total characters observed. */
    val characters: Long = 0,
    /** Total engaged milliseconds observed over those characters. */
    val millis: Long = 0
) {

    /** Characters per minute, or `null` when nothing has been observed. */
    val charactersPerMinute: Double?
        get() = if (millis <= 0 || characters <= 0) null else characters * 60_000.0 / millis

    /**
     * Whether there is enough evidence to show a time estimate at all.
     *
     * The floor is deliberately a *time* floor as well as a character one: a reader who flicked
     * through twenty pages in thirty seconds has covered plenty of characters and demonstrated
     * nothing about how fast they read.
     */
    val confident: Boolean
        get() = characters >= MIN_CHARACTERS && millis >= MIN_MILLIS && charactersPerMinute.let {
            it != null && it in PLAUSIBLE_RANGE
        }

    /**
     * Fold in a fresh observation.
     *
     * Implausible samples are dropped rather than smoothed: a "sample" covering a whole book in
     * four seconds is a jump-to-chapter, not reading, and letting it in would poison an estimate
     * that took real reading to build. Old observations decay so a genuine change of pace — a dense
     * technical book after a novel — is followed rather than averaged away forever.
     */
    fun observe(characters: Int, millis: Long): ReadingPace {
        if (characters <= 0 || millis <= 0) return this
        val rate = characters * 60_000.0 / millis
        if (rate !in PLAUSIBLE_RANGE) return this

        val decayed = if (this.characters > DECAY_ABOVE) {
            ReadingPace(
                characters = (this.characters * DECAY).toLong(),
                millis = (this.millis * DECAY).toLong()
            )
        } else {
            this
        }
        return ReadingPace(decayed.characters + characters, decayed.millis + millis)
    }

    /** Milliseconds [characters] would take at this pace, or `null` when there is no estimate. */
    fun millisFor(characters: Int): Long? {
        val rate = charactersPerMinute?.takeIf { confident } ?: return null
        if (characters <= 0) return 0
        return (characters / rate * 60_000).toLong()
    }

    companion object {
        /** Roughly a couple of pages: enough that the sample is reading rather than a glance. */
        const val MIN_CHARACTERS = 4_000L

        /** Two minutes of genuinely engaged time. */
        const val MIN_MILLIS = 120_000L

        /**
         * The band a human reading rate falls in, in characters per minute. Roughly 20–250 words a
         * minute at ~6 characters a word: below is not reading, above is skimming or scrolling.
         */
        val PLAUSIBLE_RANGE = 120.0..1_800.0

        private const val DECAY_ABOVE = 400_000L
        private const val DECAY = 0.75
    }
}

/**
 * Turning a duration into the phrase a reader wants: "4 min left", "1 hr 20 min left".
 *
 * Rounded, never precise — an estimate stated to the second claims an accuracy it does not have,
 * and reads as a countdown rather than an orientation.
 */
object TimeLeft {

    /** A short label, or `null` when there is no estimate worth showing. */
    fun label(millis: Long?): String? {
        if (millis == null) return null
        val minutes = Math.round(millis / 60_000.0).toInt()
        return when {
            minutes <= 0 -> "under a minute left"
            minutes < 60 -> "$minutes min left"
            else -> {
                val hours = minutes / 60
                val rest = minutes % 60
                if (rest == 0) "$hours hr left" else "$hours hr $rest min left"
            }
        }
    }

    /** The same phrasing, scoped to the chapter: "6 min left in chapter". */
    fun inChapter(millis: Long?): String? = label(millis)?.replace(" left", " left in chapter")
}
