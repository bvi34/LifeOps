package com.operations.suitekit

import com.operations.backupkit.AppId

/**
 * How the Operations Sandbox home screen is laid out: which tiles it draws, and in what order.
 *
 * The grid shipped in the order [SuiteApps.all] declares, which is a reasonable order and nobody's
 * own. Twelve apps is past the point where that holds: a household that lives in Logistics and
 * Health reaches past nine tiles to get to them, and the three they have never opened take the same
 * room as the two they open daily. So the order is theirs, and so is whether a tile is there at all.
 *
 * Everything here is a function of the stored document and nothing else — no Android, no store, no
 * screen — so the awkward cases are settled once, in unit tests, rather than in a Compose file:
 *
 * - **An app this build has never heard of** (an order written by a newer build, or one whose app
 *   was removed) is dropped. A key that names nothing cannot be drawn.
 * - **An app the stored order has never heard of** — a *new* app, arriving in an update — is
 *   appended rather than omitted. This is the case that matters most and the one a naive
 *   implementation gets wrong: an order stored as a whole list would silently hide every app added
 *   after it was written, and the household would have no idea there was anything to look for.
 * - **A key stored twice** is drawn once. A tile that appears in two places is a bug that looks
 *   like a feature.
 *
 * Hiding is deliberately not deleting: a hidden app keeps its place in the order, so bringing it
 * back puts it where it was rather than at the end of the grid. Nothing about a hidden app is
 * switched off — its data is there, its reminders still fire, and the Settings list is where it
 * comes back from.
 */
object SuiteHomeLayout {

    /**
     * Every hosted app in the household's order — the stored order first, then anything it does not
     * mention, in the order the suite ships. Hidden apps are included: this is the arrangement, not
     * the grid.
     */
    fun order(stored: List<String>): List<SuiteAppInfo> {
        val known = SuiteApps.all.associateBy { it.key }
        val seen = LinkedHashSet<String>()
        val chosen = stored.mapNotNull { key -> known[key]?.takeIf { seen.add(key) } }
        return chosen + SuiteApps.all.filterNot { it.key in seen }
    }

    /** The tiles the home screen actually draws. */
    fun visible(stored: List<String>, hidden: Set<String>): List<SuiteAppInfo> =
        order(stored).filterNot { it.key in hidden }

    /**
     * May [appId] be taken off the home screen?
     *
     * No, when it is the last tile standing. A launcher that can be emptied is a launcher that
     * looks broken — the dock would still reach Settings, but a household staring at an empty grid
     * has no reason to believe that is a setting rather than a failure.
     */
    fun canHide(stored: List<String>, hidden: Set<String>, appId: AppId): Boolean {
        val showing = visible(stored, hidden)
        return showing.any { it.key == appId.key } && showing.size > 1
    }

    /**
     * The order with [appId] swapped past its nearest *visible* neighbour.
     *
     * Visible, because a hidden app still holds its place: swapping with one would move the tile by
     * nothing at all and read as a dead button. The hidden app keeps the index it had, which is the
     * whole of what "hiding is not deleting" means here.
     *
     * Returns the order unchanged when there is nowhere to go, so a caller can compare the two to
     * find out whether the move is available — see [canMove].
     */
    fun moved(stored: List<String>, hidden: Set<String>, appId: AppId, forward: Boolean): List<String> {
        val keys = order(stored).map { it.key }
        val from = keys.indexOf(appId.key)
        if (from < 0) return keys
        val step = if (forward) 1 else -1
        var to = from + step
        while (to in keys.indices && keys[to] in hidden) to += step
        if (to !in keys.indices) return keys
        return keys.toMutableList().also {
            it[from] = keys[to]
            it[to] = appId.key
        }
    }

    /** Is there anywhere for [appId] to go in that direction? */
    fun canMove(stored: List<String>, hidden: Set<String>, appId: AppId, forward: Boolean): Boolean =
        moved(stored, hidden, appId, forward) != order(stored).map { it.key }
}
