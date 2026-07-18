package com.lifeops.app.game.content

import com.lifeops.app.game.map.Barrier
import com.lifeops.app.game.map.Entrance
import com.lifeops.app.game.map.GameMap
import com.lifeops.app.game.map.Tile
import com.lifeops.app.game.map.TileType
import com.lifeops.app.game.map.Zone

/**
 * Maps authored as ASCII so a new bunker is a new drawing, not new engine code (DESIGN.md #4).
 *
 * Legend:
 *   `#` wall · `0`-`9` floor of that zone · `a`-`z` a door for that barrier ·
 *   `*` a window (enemy entrance) · `p` a turret pad · `s` the player start.
 * Windows / pads / start inherit their zone from the adjacent room floor.
 */
object Maps {

    // A small bunker: a central Foyer (open at start) with West and East wings behind gold-gated
    // doors, each wing bringing its own windows online once opened.
    val NACHT: GameMap = parse(
        name = "Nacht",
        cellSize = 28f,
        zones = mapOf(
            0 to ("Foyer" to true),
            1 to ("West Wing" to false),
            2 to ("East Wing" to false),
        ),
        barriers = mapOf(
            'a' to ("West Door" to 20),
            'b' to ("East Door" to 25),
        ),
        layout = listOf(
            "#################",
            "#1111#0*0*0#2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#*111#00000#222*#",
            "#1111#0p0p0#2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#1111a00s00b2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#*111#00000#222*#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#1111#00000#2222#",
            "#1111#0*0*0#2222#",
            "#################",
        ),
    )

    val ALL: List<GameMap> = listOf(NACHT)

    private fun parse(
        name: String,
        cellSize: Float,
        zones: Map<Int, Pair<String, Boolean>>,
        barriers: Map<Char, Pair<String, Int>>,
        layout: List<String>,
    ): GameMap {
        val rows = layout.size
        val cols = layout.maxOf { it.length }
        val tiles = arrayOfNulls<Tile>(rows * cols)
        val entrances = ArrayList<Entrance>()
        val pads = ArrayList<Pair<Int, Int>>()
        var startCol = 0
        var startRow = 0

        fun charAt(c: Int, r: Int): Char = layout[r].getOrElse(c) { '#' }

        // Pass 1: everything with an explicit zone/type. Specials (* p s) resolve in pass 2.
        for (r in 0 until rows) for (c in 0 until cols) {
            val ch = charAt(c, r)
            tiles[r * cols + c] = when {
                ch in '0'..'9' -> Tile(TileType.FLOOR, zoneId = ch - '0')
                ch in 'a'..'z' -> Tile(TileType.DOOR, barrierId = ch - 'a')
                ch == '#' -> Tile(TileType.WALL)
                else -> null // pending special
            }
        }

        // Pass 2: specials become floor tiles inheriting the zone of an orthogonal room neighbour.
        fun neighbourZone(c: Int, r: Int): Int {
            for ((nc, nr) in listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)) {
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val t = tiles[nr * cols + nc]
                if (t != null && t.type == TileType.FLOOR) return t.zoneId
            }
            return 0
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            if (tiles[r * cols + c] != null) continue
            val ch = charAt(c, r)
            val zone = neighbourZone(c, r)
            when (ch) {
                '*' -> { tiles[r * cols + c] = Tile(TileType.FLOOR, zoneId = zone); entrances.add(Entrance(c, r, zone)) }
                'p' -> { tiles[r * cols + c] = Tile(TileType.FLOOR, zoneId = zone, pad = true); pads.add(c to r) }
                's' -> { tiles[r * cols + c] = Tile(TileType.FLOOR, zoneId = zone); startCol = c; startRow = r }
                else -> tiles[r * cols + c] = Tile(TileType.WALL)
            }
        }

        val zoneList = zones.entries.sortedBy { it.key }.map { (id, meta) -> Zone(id, meta.first, meta.second) }
        val barrierList = barriers.entries.sortedBy { it.key }.map { (ch, meta) -> Barrier(ch - 'a', meta.first, meta.second) }

        return GameMap(
            name = name,
            cellSize = cellSize,
            cols = cols,
            rows = rows,
            tiles = tiles.map { it ?: GameMap.WALL_TILE },
            zones = zoneList,
            barriers = barrierList,
            entrances = entrances,
            pads = pads,
            startCol = startCol,
            startRow = startRow,
        )
    }
}
