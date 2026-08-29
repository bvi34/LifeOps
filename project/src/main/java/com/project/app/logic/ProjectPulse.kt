package com.project.app.logic

/**
 * Where a project actually stands, in one object.
 *
 * The five sections each answer a different question, and none of them answers the one you ask
 * while looking at a shelf of projects: *is this thing moving?* This assembles that from the
 * others — words against the target from the outline, finished pieces from the outline's statuses,
 * cards in flight from the board, and the sheer bulk of docs, lore and events — and turns it into a
 * line of text short enough to sit under a project's name.
 *
 * It is deliberately not a score. There is no single number that means "this novel is 62% of a
 * novel", and inventing one would make the shelf sort by a fiction. What it gives instead is the
 * two or three counts that are actually true, in the project's own vocabulary.
 */
data class ProjectPulse(
    val kind: ProjectKind,
    val totals: OutlineTotals,
    val board: BoardStats,
    val docs: Int,
    val loreEntries: Int,
    val events: Int,
    val brokenLinks: Int,
    val updatedAt: Long
) {

    /** Words written against a target, when there is a target and the kind is measured in words. */
    val wordProgress: Float?
        get() = if (kind.tracksWords && totals.targetWords > 0) totals.progress else null

    val isEmpty: Boolean
        get() = totals.pieces == 0 && board.total == 0 && docs == 0 && loreEntries == 0 && events == 0

    /**
     * The one-line summary the shelf draws.
     *
     * It says the two or three things that are true and skips the rest — a project with no word
     * target does not get "0 of 0 words", and a project with no board does not get "0 cards". An
     * empty project says so, because "nothing here yet" is more useful than a row of zeroes.
     */
    val headline: String
        get() {
            if (isEmpty) return "Nothing in it yet"
            val parts = ArrayList<String>(3)

            if (kind.tracksWords && (totals.actualWords > 0 || totals.targetWords > 0)) {
                parts += if (totals.targetWords > 0) {
                    "${count(totals.actualWords)} of ${count(totals.targetWords)} words"
                } else {
                    "${count(totals.actualWords)} words"
                }
            }
            if (totals.pieces > 0) {
                parts += "${totals.piecesComplete} of ${totals.pieces} ${kind.pieces}"
            }
            if (board.inFlight > 0) {
                parts += "${board.inFlight} in flight"
            } else if (board.total > 0) {
                parts += "board clear"
            }
            if (parts.isEmpty()) {
                parts += listOfNotNull(
                    docs.takeIf { it > 0 }?.let { "$it ${plural(it, "doc")}" },
                    loreEntries.takeIf { it > 0 }?.let { "$it lore ${plural(it, "entry", "entries")}" },
                    events.takeIf { it > 0 }?.let { "$it timeline ${plural(it, "event")}" }
                )
            }
            return parts.joinToString(" · ")
        }

    companion object {
        /** 34200 → "34,200". Grouping is the whole point: word counts are read at a glance. */
        fun count(value: Int): String {
            val digits = value.toString()
            val negative = digits.startsWith("-")
            val body = if (negative) digits.drop(1) else digits
            val grouped = body.reversed().chunked(3).joinToString(",").reversed()
            return if (negative) "-$grouped" else grouped
        }

        fun plural(n: Int, singular: String, plural: String = singular + "s"): String =
            if (n == 1) singular else plural

        /** An empty project's pulse — what a brand-new project reports before anything is in it. */
        fun empty(kind: ProjectKind, updatedAt: Long = 0L) = ProjectPulse(
            kind = kind,
            totals = OutlineTotals.EMPTY,
            board = BoardStats(0, 0, 0, emptyList()),
            docs = 0,
            loreEntries = 0,
            events = 0,
            brokenLinks = 0,
            updatedAt = updatedAt
        )
    }
}
