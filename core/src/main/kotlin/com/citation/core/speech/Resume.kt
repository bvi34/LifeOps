package com.citation.core.speech

/**
 * Which place a book should open at when the eye and the voice left off in different ones.
 *
 * Listening creates a second kind of "where I was", and it is a genuinely different fact from the
 * one the reader's own scrolling saves — it happened while the app was in somebody's pocket, it can
 * be hours ahead of the last page they looked at, and it is the position they will expect to find.
 * So it is recorded separately, the way a note records the origin it was captured from, rather than
 * overwriting the reading position and hoping the two never disagree.
 *
 * They also are not the same *unit*, which is the other reason they cannot share a slot: the reading
 * position is whatever the open reading mode saves (the scroll reader stores pixels, the paged one
 * stores canonical characters), while the voice only ever knows canonical characters. [ResumePoint]
 * carries [ResumePoint.canonical] so the reader knows which it has been handed and can resolve it
 * properly instead of scrolling to a character count.
 */
object Resume {

    /**
     * The place to open at: whichever was recorded more recently.
     *
     * A place with a timestamp beats one without — rows written before positions were stamped have
     * no time to compare, and a listening position always carries one — and an exact tie goes to
     * listening, which exists only because the voice actually reached it.
     */
    fun choose(reading: SavedPlace?, listening: SavedPlace?): ResumePoint? {
        if (listening == null) return reading?.let { ResumePoint(it.chapterOrdinal, it.charOffset, canonical = false) }
        if (reading == null) return ResumePoint(listening.chapterOrdinal, listening.charOffset, canonical = true)
        val listened = listening.savedAt
        val read = reading.savedAt
        val listeningWins = when {
            listened == null && read == null -> true
            listened == null -> false
            read == null -> true
            else -> listened >= read
        }
        return if (listeningWins) {
            ResumePoint(listening.chapterOrdinal, listening.charOffset, canonical = true)
        } else {
            ResumePoint(reading.chapterOrdinal, reading.charOffset, canonical = false)
        }
    }
}

/**
 * One recorded place in a book.
 *
 * @property savedAt when it was recorded, or `null` for a row written before positions were stamped.
 */
data class SavedPlace(
    val chapterOrdinal: Int,
    val charOffset: Int,
    val savedAt: Long? = null
)

/**
 * Where to open, and in what unit.
 *
 * @property canonical `true` when [charOffset] is an offset into the chapter's text — which the
 *   reader must resolve to a page or a scroll position rather than use directly. `false` means it is
 *   whatever the reading mode that saved it stores, and can be restored the way it always was.
 */
data class ResumePoint(
    val chapterOrdinal: Int,
    val charOffset: Int,
    val canonical: Boolean
)
