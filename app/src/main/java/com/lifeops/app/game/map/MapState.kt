package com.lifeops.app.game.map

import com.lifeops.app.game.core.Vec2

/**
 * The mutable per-run state layered over a [GameMap]: which zones are unlocked, which doors are
 * open, and a per-frame flow field the enemies descend to reach the player. Kept pure (no android.*)
 * so it steps deterministically and is unit-testable.
 *
 * The flow field is a breadth-first distance map from the player's cell over walkable cells, so
 * enemies funnel through open doorways instead of walking through walls — the Nacht "zombies pour
 * through the room you opened" behaviour, for free, from one BFS per frame over a small grid.
 */
class MapState(val map: GameMap) {

    val unlockedZones: MutableSet<Int> = HashSet<Int>().apply {
        map.zones.filter { it.startsUnlocked }.forEach { add(it.id) }
    }
    val openBarriers: MutableSet<Int> = HashSet()

    /**
     * Cells the boss has smashed open (DESIGN.md §7). A breach is permanent for the run, walkable
     * regardless of the underlying tile, and — unlike a bought door — can never be barricaded shut.
     */
    val breaches: MutableSet<Int> = HashSet()

    private val flow = IntArray(map.cols * map.rows) { -1 }

    private fun index(c: Int, r: Int) = r * map.cols + c
    private fun isBorder(c: Int, r: Int) = c == 0 || r == 0 || c == map.cols - 1 || r == map.rows - 1

    fun isBreached(c: Int, r: Int): Boolean = map.inBounds(c, r) && index(c, r) in breaches

    /** Whether a cell could ever be barricaded shut — boss breaches never can be. */
    fun isBarricadeable(c: Int, r: Int): Boolean = !isBreached(c, r)

    fun walkable(c: Int, r: Int): Boolean {
        if (!map.inBounds(c, r)) return false
        if (index(c, r) in breaches) return true
        val t = map.tileAt(c, r)
        return when (t.type) {
            TileType.FLOOR -> t.zoneId in unlockedZones
            TileType.DOOR -> t.barrierId in openBarriers
            TileType.WALL -> false
        }
    }

    /**
     * Smash a wall/door cell open for good. The border is never breachable (the bunker can't be
     * opened to the void). Breaching a door force-opens it; either way, any room the newly-open cell
     * borders is revealed, so the boss can tear its way into a sealed wing.
     */
    fun breach(c: Int, r: Int): Boolean {
        if (!map.inBounds(c, r) || isBorder(c, r)) return false
        if (index(c, r) in breaches) return false
        breaches.add(index(c, r))
        val t = map.tileAt(c, r)
        if (t.type == TileType.DOOR) {
            openBarriers.add(t.barrierId)
            map.barrierZones[t.barrierId]?.forEach { unlockedZones.add(it) }
        }
        // Reveal any room the breach now exposes.
        for ((nc, nr) in listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)) {
            val nt = map.tileAt(nc, nr)
            if (nt.type == TileType.FLOOR) unlockedZones.add(nt.zoneId)
        }
        return true
    }

    fun walkableWorld(p: Vec2): Boolean = walkable(map.colOf(p.x), map.rowOf(p.y))

    /** Open a barrier and reveal every locked room it borders. */
    fun unlock(barrierId: Int) {
        openBarriers.add(barrierId)
        map.barrierZones[barrierId]?.forEach { unlockedZones.add(it) }
    }

    /** Windows currently spawning enemies — those in unlocked rooms. */
    fun activeEntrances(): List<Entrance> = map.entrances.filter { it.zoneId in unlockedZones }

    /** The closest still-locked barrier within [maxDist] of [p], or null — powers the buy prompt. */
    fun nearbyLockedBarrier(p: Vec2, maxDist: Float): Barrier? {
        var best: Barrier? = null
        var bestDist = maxDist
        for (b in map.barriers) {
            if (b.id in openBarriers) continue
            val cells = map.doorCells[b.id] ?: continue
            for ((c, r) in cells) {
                val d = map.cellCenter(c, r).distanceTo(p)
                if (d <= bestDist) { bestDist = d; best = b }
            }
        }
        return best
    }

    /** Rebuild the flow field with the player's cell as the goal. Cheap: one BFS over the grid. */
    fun computeFlow(playerCol: Int, playerRow: Int) {
        flow.fill(-1)
        if (!walkable(playerCol, playerRow)) return
        val cols = map.cols
        val queue = ArrayDeque<Int>()
        val start = playerRow * cols + playerCol
        flow[start] = 0
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cc = cur % cols
            val cr = cur / cols
            val d = flow[cur]
            for ((nc, nr) in listOf(cc - 1 to cr, cc + 1 to cr, cc to cr - 1, cc to cr + 1)) {
                if (!map.inBounds(nc, nr)) continue
                val ni = nr * cols + nc
                if (flow[ni] == -1 && walkable(nc, nr)) {
                    flow[ni] = d + 1
                    queue.add(ni)
                }
            }
        }
    }

    /**
     * Unit direction an enemy at [p] should travel to approach the player, descending the flow
     * field. Falls back to a straight line toward the player in the goal cell or when the flow is
     * unavailable (e.g. an enemy momentarily off the field).
     */
    fun flowDir(p: Vec2, playerPos: Vec2): Vec2 {
        val c = map.colOf(p.x)
        val r = map.rowOf(p.y)
        val cols = map.cols
        val here = if (map.inBounds(c, r)) flow[r * cols + c] else -1
        if (here <= 0) return (playerPos - p).normalized()
        var bestC = c
        var bestR = r
        var bestD = here
        for (dc in -1..1) for (dr in -1..1) {
            if (dc == 0 && dr == 0) continue
            val nc = c + dc
            val nr = r + dr
            if (!map.inBounds(nc, nr) || !walkable(nc, nr)) continue
            val f = flow[nr * cols + nc]
            if (f in 0 until bestD) { bestD = f; bestC = nc; bestR = nr }
        }
        if (bestC == c && bestR == r) return (playerPos - p).normalized()
        return (map.cellCenter(bestC, bestR) - p).normalized()
    }
}
