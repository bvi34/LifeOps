package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.Maps
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.map.MapState
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTest {

    private val map = Maps.NACHT

    @Test
    fun mapParsesIntoZonesBarriersEntrancesAndPads() {
        assertEquals(3, map.zones.size)
        assertEquals(2, map.barriers.size)
        // 4 Foyer windows + 2 West + 2 East.
        assertEquals(8, map.entrances.size)
        assertEquals(2, map.pads.size)
        // Start is a floor cell in the Foyer (zone 0).
        assertEquals(0, map.tileAt(map.startCol, map.startRow).zoneId)
    }

    @Test
    fun onlyTheFoyerIsWalkableUntilADoorIsBought() {
        val s = MapState(map)
        assertTrue(s.walkable(map.startCol, map.startRow))
        // Foyer windows are the only active spawn points at the start.
        assertEquals(4, s.activeEntrances().size)
        // A West-wing floor cell is sealed off.
        assertFalse(s.walkable(2, 9))
    }

    @Test
    fun buyingADoorRevealsItsRoomAndItsWindows() {
        val s = MapState(map)
        // Barrier 0 (West Door) borders zones {0,1}.
        assertEquals(setOf(0, 1), map.barrierZones[0])
        s.unlock(0)
        assertTrue(1 in s.unlockedZones)          // West wing (zone 1) now open
        assertTrue(s.walkable(2, 9))              // west floor now walkable
        assertEquals(6, s.activeEntrances().size) // +2 west windows
    }

    @Test
    fun flowFieldPointsEnemiesTowardThePlayer() {
        val s = MapState(map)
        s.computeFlow(map.startCol, map.startRow) // goal = player at (8,9)
        // An enemy a few cells above the player should be steered downward toward it.
        val above = map.cellCenter(map.startCol, map.startRow - 4)
        val dir = s.flowDir(above, map.cellCenter(map.startCol, map.startRow))
        assertTrue("should head down toward the player", dir.y > 0.2f)
    }

    @Test
    fun nearbyLockedBarrierDetectsAnAdjacentDoor() {
        val s = MapState(map)
        // Foyer cell just right of the West Door (door is at col 5, row 9).
        val beside = map.cellCenter(6, 9)
        assertNotNull(s.nearbyLockedBarrier(beside, 42f))
        assertEquals(0, s.nearbyLockedBarrier(beside, 42f)?.id)
        // Far away: no prompt.
        assertNull(s.nearbyLockedBarrier(map.cellCenter(9, 3), 42f))
    }

    // --- Engine integration -----------------------------------------------------------------

    private fun engine(gold: Int = 50) = RunEngine(
        RunConfig(
            weapon = StartingWeapon.GATLING, levelCap = 20, maxHealth = 2000f,
            startingGold = gold, seed = 3L, waves = 3, challengeMode = ChallengeMode.NONE,
        )
    )

    @Test
    fun playerCannotWalkThroughWallsIntoLockedRooms() {
        val e = engine()
        repeat(240) { e.step(1f / 60f, RunInput(Vec2(-1f, 0f))) } // hold left
        // Blocked by the sealed West Door: never crosses out of the Foyer columns.
        assertTrue(map.colOf(e.player.pos.x) >= 6)
        assertTrue(e.mapState.walkableWorld(e.player.pos))
    }

    @Test
    fun buyingADoorSpendsGoldAndOpensTheRoom() {
        val e = engine(gold = 50)
        repeat(240) { e.step(1f / 60f, RunInput(Vec2(-1f, 0f))) } // walk up to the West Door
        val goldBefore = e.player.gold
        val bought = e.buyBarrier(0)
        assertTrue("should be able to buy the door when adjacent and funded", bought)
        assertEquals(goldBefore - 20, e.player.gold)
        assertTrue(1 in e.mapState.unlockedZones)
    }

    @Test
    fun enemiesSpawnInsideUnlockedRooms() {
        val e = engine()
        repeat(300) { e.step(1f / 60f, RunInput()) }
        val enemies = e.enemies
        assertTrue(enemies.isNotEmpty())
        assertTrue("every enemy should stand on walkable, unlocked floor", enemies.all { e.mapState.walkableWorld(it.pos) })
    }

    // --- Boss breaches ----------------------------------------------------------------------

    @Test
    fun bossBreachOpensAWallPermanentlyAndCannotBeBarricaded() {
        val s = MapState(map)
        // A divider-wall cell between Foyer and West (the door is at row 9; row 3 is solid wall).
        assertFalse(s.walkable(5, 3))
        assertTrue(s.breach(5, 3))
        assertTrue(s.walkable(5, 3))
        assertFalse(s.isBarricadeable(5, 3))
        assertTrue(1 in s.unlockedZones) // tearing the divider reveals the West wing
    }

    @Test
    fun theBunkerBorderCanNeverBeBreached() {
        val s = MapState(map)
        assertFalse(s.breach(0, 5))
        assertFalse(s.walkable(0, 5))
    }

    @Test
    fun breachingADoorForcesItOpen() {
        val s = MapState(map)
        assertFalse(s.walkable(5, 9)) // West Door, still shut
        assertTrue(s.breach(5, 9))
        assertTrue(0 in s.openBarriers)
        assertTrue(1 in s.unlockedZones)
    }
}
