package com.logistics.app.logic

import com.logistics.app.data.model.RecipeNote
import kotlin.math.roundToInt

/**
 * What a recipe's notes add up to, for the one line a recipe row can spare: how many notes there
 * are, and what the ones carrying a verdict average to.
 *
 * [averageRating] is null when nothing has been rated — *not* zero. The difference is the whole
 * reason this is a type rather than a number: "no verdict yet" and "everybody hated it" must not
 * print the same, and a note that says "needs 10 more minutes" without a star rating is the common
 * case, not an edge one. For the same reason [ratedCount] is separate from [count]: nine notes and
 * one rating is a well-documented recipe with one opinion, and the row should say so.
 *
 * Framework-free and unit-tested, like the rest of `logic/`.
 */
data class RecipeNoteSummary(
    val count: Int,
    val ratedCount: Int,
    val averageRating: Double?
) {
    val hasRating: Boolean get() = averageRating != null

    companion object {
        val EMPTY = RecipeNoteSummary(count = 0, ratedCount = 0, averageRating = null)
    }
}

object RecipeNoteSummaries {

    /** Rolls a recipe's notes into the line a row shows. Ratings outside 1–5 are ignored rather than
     *  trusted: a stray value from an old row shouldn't drag an average somewhere impossible. */
    fun summarize(notes: List<RecipeNote>): RecipeNoteSummary {
        if (notes.isEmpty()) return RecipeNoteSummary.EMPTY
        val ratings = notes.mapNotNull { it.rating }.filter { it in MIN_STARS..MAX_STARS }
        return RecipeNoteSummary(
            count = notes.size,
            ratedCount = ratings.size,
            averageRating = if (ratings.isEmpty()) null else ratings.sum().toDouble() / ratings.size
        )
    }

    /** "4.5", or "4" when it lands on a whole star — a trailing ".0" reads like a measurement. */
    fun formatRating(average: Double): String {
        val rounded = (average * 10).roundToInt() / 10.0
        return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
    }

    /** The one-line summary for a recipe row, or null when there is nothing to say yet. */
    fun label(summary: RecipeNoteSummary): String? {
        if (summary.count == 0) return null
        val notes = "${summary.count} note${if (summary.count == 1) "" else "s"}"
        val average = summary.averageRating ?: return notes
        return "★ ${formatRating(average)} · $notes"
    }

    const val MIN_STARS = 1
    const val MAX_STARS = 5
}
