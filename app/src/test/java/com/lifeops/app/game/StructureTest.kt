package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureTest {

    private fun engine(gold: Int = 200) = RunEngine(
        RunConfig(
            weapon = StartingWeapon.GATLING, levelCap = 20, maxHealth = 5000f,
            startingGold = gold, seed = 3L, waves = 3, challengeMode = ChallengeMode.NONE,
        )
    )

    /** A cell a couple of tiles from the player (inside the arena, not the player's own cell). */
    private fun nearby(e: RunEngine, dx: Float = 2f, dy: Float = 0f): Vec2 =
        e.player.pos + Vec2(e.arena.cellSize * dx, e.arena.cellSize * dy)

    @Test
    fun placingSpendsGoldAndOccupiesTheCell() {
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.TURRET, nearby(e)))
        assertEquals(75, e.player.gold)
        assertEquals(1, e.structures.size)
        // Same cell can't be used twice.
        assertFalse(e.placeStructure(StructureType.BARRICADE, nearby(e)))
        assertEquals(1, e.structures.size)
    }

    @Test
    fun placementIsRejectedWhenUnaffordableOrInvalid() {
        engine(gold = 5).let { assertFalse(it.placeStructure(StructureType.TURRET, nearby(it))) }
        engine(gold = 100).let { assertFalse("can't build on your own cell", it.placeStructure(StructureType.BARRICADE, it.player.pos)) }
        engine(gold = 100).let { assertFalse("can't build outside the arena", it.placeStructure(StructureType.TURRET, Vec2(-100f, -100f))) }
    }

    @Test
    fun turretsFireAtEnemies() {
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.TURRET, nearby(e)))
        var turretShotSeen = false
        repeat(400) {
            e.step(1f / 60f, RunInput())
            // Turret projectiles are friendly but owned by the turret, not the player (id 0).
            if (e.projectiles.any { it.friendly && it.ownerId != RunEngine.PLAYER_ID }) turretShotSeen = true
        }
        assertTrue("a placed turret should shoot when enemies are in range", turretShotSeen)
    }

    @Test
    fun enemiesAttackAndDamagePlacedStructures() {
        val e = engine(gold = 100)
        // A barricade right next to the player: converging enemies get blocked on it and attack.
        assertTrue(e.placeStructure(StructureType.BARRICADE, nearby(e, dx = 1f)))
        val id = e.structures.first().id
        val maxHp = e.structures.first().maxHp
        repeat(1500) { e.step(1f / 60f, RunInput()) }
        val still = e.structures.firstOrNull { it.id == id }
        assertTrue("the barricade should have been attacked (damaged or destroyed)", still == null || still.hp < maxHp)
    }
}
