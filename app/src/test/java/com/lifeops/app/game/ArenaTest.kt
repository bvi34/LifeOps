package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.RunSeed
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.ArenaState
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTest {

    @Test
    fun startsSmallAndGrowsWhenExpanded() {
        val a = ArenaState()
        assertEquals(0, a.stage)
        val w0 = a.max().x - a.min().x
        val h0 = a.max().y - a.min().y
        // The active region starts well inside the fixed world.
        assertTrue(w0 < a.worldSize.x)
        assertTrue(h0 < a.worldSize.y)
        assertTrue(a.expand())
        assertEquals(1, a.stage)
        assertTrue("width grows on expand", a.max().x - a.min().x > w0)
        assertTrue("height grows on expand", a.max().y - a.min().y > h0)
    }

    @Test
    fun expansionCostsEscalateThenRunOut() {
        val a = ArenaState()
        val c0 = a.nextCost()!!
        a.expand()
        val c1 = a.nextCost()!!
        assertTrue("cost escalates", c1 > c0)
        while (a.canExpand()) a.expand()
        assertNull(a.nextCost())
        assertFalse(a.expand())
    }

    @Test
    fun clampKeepsAPointInsideTheActiveRegion() {
        val a = ArenaState()
        val outside = Vec2(a.worldSize.x, a.worldSize.y) // far bottom-right corner of the world
        val clamped = a.clamp(outside, 10f)
        assertTrue(clamped.x <= a.max().x - 10f + 0.001f)
        assertTrue(clamped.y <= a.max().y - 10f + 0.001f)
        assertTrue(a.contains(clamped))
    }

    @Test
    fun edgeSpawnsLandOnTheActiveBoundary() {
        val a = ArenaState()
        val rng = RunSeed(11L)
        repeat(200) {
            val p = a.randomEdge(rng)
            assertTrue(p.x in (a.min().x - 1f)..(a.max().x + 1f))
            assertTrue(p.y in (a.min().y - 1f)..(a.max().y + 1f))
        }
    }

    // --- Engine integration -----------------------------------------------------------------

    private fun engine(gold: Int = 0) = RunEngine(
        RunConfig(
            weapon = StartingWeapon.GATLING, levelCap = 20, maxHealth = 2000f,
            startingGold = gold, seed = 3L, waves = 3, challengeMode = ChallengeMode.NONE,
        )
    )

    @Test
    fun playerStaysInsideTheArena() {
        val e = engine()
        repeat(400) { e.step(1f / 60f, RunInput(Vec2(-1f, -1f))) } // shove into a corner
        assertTrue(e.arena.contains(e.player.pos))
    }

    @Test
    fun buyingExpansionSpendsGoldAndGrowsTheArena() {
        val e = engine(gold = 100)
        val before = e.arena.max().x - e.arena.min().x
        assertTrue(e.buyExpansion())
        assertEquals(70, e.player.gold) // 100 - 30
        assertEquals(1, e.arena.stage)
        assertTrue(e.arena.max().x - e.arena.min().x > before)
    }

    @Test
    fun enemiesStayInsideTheArena() {
        val e = engine()
        repeat(300) { e.step(1f / 60f, RunInput()) }
        assertTrue(e.enemies.isNotEmpty())
        assertTrue(e.enemies.all { e.arena.contains(it.pos) })
    }
}
