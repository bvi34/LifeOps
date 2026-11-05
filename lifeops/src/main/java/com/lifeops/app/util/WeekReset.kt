package com.lifeops.app.util

/**
 * "Scar past weeks" — the way back after time away. You pick how many of the weeks right before
 * this one to write off (the last X weeks) and give a reason. Each becomes a *skipped* week: it is
 * closed if still open, it draws as a grey scar on the Growth Record, and the reason is kept
 * beside it. Optionally the weekly / repeating tasks are cleared as well (see TaskRepository).
 *
 * Launching after a gap already auto-closes the missed weeks (TaskRepository.catchUpClose), so
 * the weeks are picked by position — "the last X" — not by whether they still look open. They can
 * be picked here or by tapping a bar on the Growth Record; weeks with a few hours logged can be
 * scarred too. A week already scarred is simply re-marked with the new reason.
 *
 * Today's week is never scarred: it is the fresh start. Pure and Android-free.
 */
object WeekReset {

    /** The most weeks one reset can reach back. */
    const val MAX_WEEKS = 52

    /**
     * A week with less than this logged still reads as time away when suggesting what to scar — an
     * hour squeezed in during a trip shouldn't stop the suggestion. Any week can be picked by hand.
     */
    const val QUIET_MINUTES = 180

    /** One week as the planner sees it. */
    data class WeekInfo(
        val weekId: String,
        /** [DateUtil.weekIndexFor] of the week's start. */
        val weekIndex: Int,
        val isClosed: Boolean,
        val isSkipped: Boolean,
        /** True if the week saw real use: at least [QUIET_MINUTES] logged. */
        val wasUsed: Boolean
    )

    data class Plan(
        /** Today's week index; the scarred weeks end right before it. */
        val currentIndex: Int,
        /** How many past weeks can be picked (1..this). Zero when there is no past week at all. */
        val availableCount: Int,
        /**
         * The run of untouched, not-yet-scarred weeks right before today's — the default pick and
         * what the banner offers. Zero when last week was used.
         */
        val suggestedCount: Int
    ) {
        /** The week indices "the last [count] weeks" covers, oldest first. */
        fun range(count: Int): IntRange = rangeFor(currentIndex, count.coerceIn(0, availableCount))
    }

    fun rangeFor(currentIndex: Int, count: Int): IntRange = (currentIndex - count) until currentIndex

    /**
     * [weeks] is every week the app holds, in any order; [currentIndex] is today's week index.
     * A stale open week counts toward [Plan.availableCount] — the reset closes it on the way.
     */
    fun plan(weeks: List<WeekInfo>, currentIndex: Int): Plan {
        val oldest = weeks.minOfOrNull { it.weekIndex }
        val available = if (oldest == null) 0 else (currentIndex - oldest).coerceIn(0, MAX_WEEKS)

        // Suggested: walk back from last week while each week went untouched and isn't scarred.
        // A stale open week (and anything after it, not yet minted) is untouched by definition.
        val byIndex = weeks.associateBy { it.weekIndex }
        val open = weeks.filter { !it.isClosed }.maxByOrNull { it.weekIndex }?.weekIndex
        var suggested = 0
        var i = currentIndex - 1
        while (suggested < available) {
            val w = byIndex[i]
            val untouched = when {
                open != null && i >= open -> w?.isSkipped != true
                w == null -> false
                else -> !w.wasUsed && !w.isSkipped
            }
            if (!untouched) break
            suggested++
            i--
        }
        return Plan(currentIndex, available, suggested)
    }

    /** The CSV / detail-panel note for a skipped week: the reason, flagged when tasks were cleared. */
    fun note(reason: String?, trueReset: Boolean): String {
        val r = reason?.trim().orEmpty()
        return when {
            trueReset && r.isNotEmpty() -> "True reset: $r"
            trueReset -> "True reset"
            else -> r
        }
    }
}
