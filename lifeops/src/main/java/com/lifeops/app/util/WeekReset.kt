package com.lifeops.app.util

/**
 * "Reset to current week" — the way back after weeks away. Every week the app was left
 * untouched is written off as *skipped*: it is closed (if it is still open), it draws as a
 * grey scar on the Growth Record, and the reason given for the reset is kept beside it.
 *
 * Two kinds of week get scarred:
 *   - **Stale open weeks.** The open week, and every week after it, that ended before today's
 *     week began. These get closed by the reset itself.
 *   - **Untouched closed weeks.** Launching the app after a gap already auto-closes the missed
 *     weeks (see TaskRepository.catchUpClose), so by the time you see This Week the gap is often
 *     already sealed. Those weeks are found by walking back from the newest closed week while each
 *     one shows no sign of use — no time logged, nothing completed, no close-out answers — and
 *     stopping at the first that does. They only gain the skip mark; their sealed record is left
 *     exactly as it was.
 *
 * Today's week is never closed: it is the week you are resetting *to*. Pure and Android-free.
 */
object WeekReset {

    /** One week as the planner sees it. */
    data class WeekInfo(
        val weekId: String,
        /** [DateUtil.weekIndexFor] of the week's start. */
        val weekIndex: Int,
        val isClosed: Boolean,
        val isSkipped: Boolean,
        /** True if anything happened in the week: time logged, a task completed, a close-out answer. */
        val wasUsed: Boolean
    )

    data class Plan(
        /** Already-closed weeks that will gain the skip mark, oldest first. */
        val closedWeekIds: List<String>,
        /** Stale weeks the reset will close (the open week plus any it has to mint to catch up). */
        val weeksToClose: Int,
        /** Week index of the oldest scarred week, or null when nothing will be scarred. */
        val firstIndex: Int?,
        /** Week index of the newest scarred week (always the week before today's), or null. */
        val lastIndex: Int?
    ) {
        /** "How many weeks are being scarred." */
        val scarCount: Int get() = closedWeekIds.size + weeksToClose
    }

    val NOTHING = Plan(emptyList(), 0, null, null)

    /**
     * [weeks] is every week the app holds, in any order; [currentIndex] is today's week index.
     * Weeks already marked skipped stop the backwards walk, so running the reset twice in a row
     * never scars the same gap twice.
     */
    fun plan(weeks: List<WeekInfo>, currentIndex: Int): Plan {
        val open = weeks.filter { !it.isClosed }.maxByOrNull { it.weekIndex }
        val staleFrom = open?.weekIndex?.takeIf { it < currentIndex }
        val weeksToClose = staleFrom?.let { currentIndex - it } ?: 0

        // Walk back from the newest closed week that sits before the stale stretch (or before
        // today's week when nothing is stale) while each week went untouched.
        val boundary = staleFrom ?: currentIndex
        val closedBefore = weeks
            .filter { it.isClosed && it.weekIndex < boundary }
            .sortedByDescending { it.weekIndex }
        val untouched = ArrayList<WeekInfo>()
        for (w in closedBefore) {
            if (w.wasUsed || w.isSkipped) break
            untouched.add(w)
        }
        // Only a gap that reaches right up to the stale stretch / today's week counts. A run of
        // quiet weeks long ago, followed by a used week, is history — not this absence.
        val newestUntouched = untouched.firstOrNull()?.weekIndex
        val closedIds = if (newestUntouched != null && newestUntouched == boundary - 1)
            untouched.reversed().map { it.weekId } else emptyList()

        if (closedIds.isEmpty() && weeksToClose == 0) return NOTHING
        val firstIndex = when {
            closedIds.isNotEmpty() -> untouched.last().weekIndex
            else -> staleFrom
        }
        return Plan(
            closedWeekIds = closedIds,
            weeksToClose = weeksToClose,
            firstIndex = firstIndex,
            lastIndex = currentIndex - 1
        )
    }

    /** The CSV / detail-panel note for a skipped week: the reason, flagged when it was a true reset. */
    fun note(reason: String?, trueReset: Boolean): String {
        val r = reason?.trim().orEmpty()
        return when {
            trueReset && r.isNotEmpty() -> "True reset: $r"
            trueReset -> "True reset"
            else -> r
        }
    }
}
