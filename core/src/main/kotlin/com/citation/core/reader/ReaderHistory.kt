package com.citation.core.reader

/** A place in the open book: the chapter, and where in its canonical text. */
data class ReadingPlace(val chapterOrdinal: Int, val offset: Int)

/**
 * Where the reader was before it jumped, so it can go back.
 *
 * The thing that makes a jump safe to take. A reader who follows a note reference, a cross-reference
 * or a contents entry has not stopped reading the sentence they were in — they mean to come back to
 * it — and without this, coming back means scrolling for the paragraph you just left, which is
 * enough of a cost that people stop following references at all.
 *
 * A stack rather than one slot because the jumps nest: a note that cites another note, a
 * cross-reference inside an endnote. It is bounded, because a reader who has taken forty jumps is
 * not going back through all of them and a list that grows forever is a leak; the oldest go first.
 *
 * Immutable, so the whole thing is one value the reader holds and one that can be tested without a
 * device.
 */
data class ReaderHistory(val places: List<ReadingPlace> = emptyList()) {

    /** Where going back would land, or `null` when there is nowhere to go back to. */
    val last: ReadingPlace? get() = places.lastOrNull()

    val isEmpty: Boolean get() = places.isEmpty()

    /**
     * Remember [place] as somewhere to come back to.
     *
     * A place identical to the one on top is not remembered twice: tapping the same note reference
     * again, or a second reference in the same sentence, would otherwise need two taps to undo one
     * jump.
     */
    fun pushed(place: ReadingPlace): ReaderHistory {
        if (place == last) return this
        return ReaderHistory((places + place).takeLast(MAX))
    }

    /** Take the last place back off, with the history that remains. */
    fun popped(): Pair<ReadingPlace?, ReaderHistory> {
        val place = last ?: return null to this
        return place to ReaderHistory(places.dropLast(1))
    }

    /** Forget everything — a different book is a different set of places. */
    fun cleared(): ReaderHistory = if (isEmpty) this else ReaderHistory()

    companion object {
        /** Deep enough for nested notes, shallow enough that it cannot grow without bound. */
        const val MAX = 20
    }
}
