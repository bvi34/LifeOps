package com.lifeops.app.game.map

import com.lifeops.app.game.core.Vec2

/**
 * A fixed grid map — a Nacht-der-Untoten-style bunker (DESIGN.md §7 holdout). One tile grid gives
 * us everything at once: walls, gold-gated doors that unlock new rooms, fixed window entrances
 * where enemies climb in, and a placement grid whose floor cells are the pads a turret can land on.
 *
 * Static geometry only — the mutable run state (which zones are unlocked, which doors are open)
 * lives in [MapState]. Authored from ASCII in content/Maps.kt, so a new map is a new drawing.
 */
enum class TileType { WALL, FLOOR, DOOR }

/** One grid cell. FLOOR carries its [zoneId] and whether it is a turret [pad]; DOOR its [barrierId]. */
data class Tile(
    val type: TileType,
    val zoneId: Int = -1,
    val barrierId: Int = -1,
    val pad: Boolean = false,
)

/** A room. Rooms other than the start are unlockable by opening a [Barrier] that borders them. */
data class Zone(val id: Int, val name: String, val startsUnlocked: Boolean)

/** A door/debris pile between two zones, opened once for a one-time gold [cost] (a §7 gold sink). */
data class Barrier(val id: Int, val name: String, val cost: Int)

/** A window an enemy climbs through. Only spawns while its [zoneId] room is unlocked. */
data class Entrance(val col: Int, val row: Int, val zoneId: Int)

class GameMap(
    val name: String,
    val cellSize: Float,
    val cols: Int,
    val rows: Int,
    /** Row-major, size cols*rows. */
    val tiles: List<Tile>,
    val zones: List<Zone>,
    val barriers: List<Barrier>,
    val entrances: List<Entrance>,
    val pads: List<Pair<Int, Int>>,
    val startCol: Int,
    val startRow: Int,
) {
    val worldSize: Vec2 = Vec2(cols * cellSize, rows * cellSize)

    fun inBounds(c: Int, r: Int): Boolean = c in 0 until cols && r in 0 until rows
    fun tileAt(c: Int, r: Int): Tile = if (inBounds(c, r)) tiles[r * cols + c] else WALL_TILE
    fun colOf(x: Float): Int = (x / cellSize).toInt().coerceIn(0, cols - 1)
    fun rowOf(y: Float): Int = (y / cellSize).toInt().coerceIn(0, rows - 1)
    fun cellCenter(c: Int, r: Int): Vec2 = Vec2((c + 0.5f) * cellSize, (r + 0.5f) * cellSize)

    /** Door cells grouped by barrier, and the zones each barrier borders — derived once up front. */
    val doorCells: Map<Int, List<Pair<Int, Int>>>
    val barrierZones: Map<Int, Set<Int>>

    init {
        val cells = HashMap<Int, MutableList<Pair<Int, Int>>>()
        val zonesByBarrier = HashMap<Int, MutableSet<Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val t = tiles[r * cols + c]
            if (t.type == TileType.DOOR) {
                cells.getOrPut(t.barrierId) { ArrayList() }.add(c to r)
                val zs = zonesByBarrier.getOrPut(t.barrierId) { HashSet() }
                for ((nc, nr) in listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)) {
                    val nt = tileAt(nc, nr)
                    if (nt.type == TileType.FLOOR) zs.add(nt.zoneId)
                }
            }
        }
        doorCells = cells
        barrierZones = zonesByBarrier
    }

    companion object {
        val WALL_TILE = Tile(TileType.WALL)
    }
}
