package com.maintenance.app.logic

/** Where a docket line came from — the two things that can be owed on an asset. */
enum class DocketSource { UPKEEP, COVERAGE }

/**
 * One line on the docket: something an asset needs, with the asset it belongs to already attached.
 *
 * The asset's name travels *on* the entry rather than being looked up by the screen, because the
 * docket's whole value is that it crosses assets — "the truck's inspection and the furnace filter,
 * both this week" — and a list that makes the reader resolve ids to names is a list they read as a
 * table instead of as a to-do.
 */
data class DocketEntry(
    val id: String,
    val assetId: String,
    val assetName: String,
    val title: String,
    val detail: String,
    val status: DueStatus,
    val dueAt: Long?,
    val source: DocketSource
)

/**
 * The front door: everything owed across every asset, in the order it wants attention.
 *
 * This is the app's answer to the only question it exists to answer — *what needs doing?* — and it
 * is assembled rather than stored, so it cannot go stale and there is nothing to reconcile after a
 * restore.
 *
 * The sort is deliberately not "by date". Ordering purely by due date puts an inspection that
 * lapsed in March above one that lapsed last week, which is backwards: the recent miss is the one
 * you can still do something about cheaply, and the March one has already cost what it is going to
 * cost. So entries group by *status* first — overdue, then soon, then everything with a date, then
 * the ones that cannot be dated — and within overdue the **most recently** missed comes first.
 */
object Docket {

    private fun rank(status: DueStatus): Int = when (status) {
        DueStatus.OVERDUE -> 0
        DueStatus.DUE_SOON -> 1
        DueStatus.SCHEDULED -> 2
        DueStatus.NEEDS_BASELINE -> 3
        DueStatus.DORMANT -> 4
    }

    /** Order a docket. Everything is kept; deciding what to *show* is the screen's business. */
    fun order(entries: List<DocketEntry>): List<DocketEntry> =
        entries.sortedWith(
            compareBy<DocketEntry> { rank(it.status) }
                .thenBy { entry ->
                    when {
                        entry.dueAt == null -> Long.MAX_VALUE
                        // Overdue reads most-recent-first; everything else soonest-first.
                        entry.status == DueStatus.OVERDUE -> -entry.dueAt
                        else -> entry.dueAt
                    }
                }
                .thenBy { it.assetName.lowercase() }
                .thenBy { it.title.lowercase() }
        )

    /** Just the lines worth interrupting somebody about. */
    fun pressing(entries: List<DocketEntry>): List<DocketEntry> =
        order(entries).filter { it.status.isPressing }

    /**
     * The one line at the top of the screen. It says nothing when there is nothing to say, which is
     * the point: an empty docket should look empty, not report "0 overdue · 0 due soon".
     */
    fun headline(entries: List<DocketEntry>): String {
        val overdue = entries.count { it.status == DueStatus.OVERDUE }
        val soon = entries.count { it.status == DueStatus.DUE_SOON }
        val parts = buildList {
            if (overdue > 0) add("$overdue overdue")
            if (soon > 0) add("$soon due soon")
        }
        return if (parts.isEmpty()) "Nothing due" else parts.joinToString(" · ")
    }
}
