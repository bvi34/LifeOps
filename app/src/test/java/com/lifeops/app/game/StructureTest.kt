package com.lifeops.app.game

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Projectile
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureTest {

    private fun engine(gold: Int = 200) = RunEngine(
        RunConfig(
            weapon = StartingWeapon.GATLING, levelCap = 20, maxHits = 999,
            startingGold = gold, seed = 3L, waves = 3, challengeMode = ChallengeMode.NONE,
        )
    )

    /** A cell a couple of tiles from the player (inside the arena, not the player's own cell). */
    private fun nearby(e: RunEngine, dx: Float = 2f, dy: Float = 0f): Vec2 =
        e.player.pos + Vec2(e.arena.cellSize * dx, e.arena.cellSize * dy)

    @Test
    fun buildingABarricadeSpendsGoldAndOccupiesTheCell() {
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.BARRICADE, nearby(e)))
        assertEquals(90, e.player.gold)
        assertEquals(1, e.structures.size)
        // Same cell can't be used twice.
        assertFalse(e.placeStructure(StructureType.BARRICADE, nearby(e)))
        assertEquals(1, e.structures.size)
    }

    @Test
    fun turretArtifactIsNotAGoldBuy() {
        // The auto-deployed Turret artifact is not placeable; the gold turret is the static Sentry.
        val e = engine(gold = 500)
        assertFalse("the artifact turret can't be placed with gold", e.placeStructure(StructureType.TURRET, nearby(e)))
        assertEquals(500, e.player.gold)
        assertTrue(e.structures.isEmpty())
    }

    @Test
    fun sentryIsAStaticGoldTurretThatFires() {
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.SENTRY, nearby(e)))
        assertEquals(75, e.player.gold)
        val s = e.structures.first()
        // Static and permanent — not an auto-turret, no TTL, and it never moves off its cell.
        assertFalse(s.artifactTurret)
        assertEquals(Float.POSITIVE_INFINITY, s.ttl, 0f)
        val where = s.pos
        var fired = false
        repeat(400) {
            e.step(1f / 60f, RunInput())
            if (e.projectiles.any { it.friendly && it.ownerId != RunEngine.PLAYER_ID }) fired = true
        }
        assertTrue("a static Sentry should shoot when enemies are in range", fired)
        assertEquals("a Sentry stays exactly where it was placed", where, e.structures.firstOrNull { it.id == s.id }?.pos ?: where)
    }

    @Test
    fun blockingStructuresStopEnemyShotsButNotFriendly() {
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.BARRICADE, nearby(e)))
        val cell = e.structures.first().pos
        // An enemy shot sitting on the barricade's cell is stopped; a friendly one passes over it.
        e.projectiles.add(Projectile(id = 90001, ownerId = 1, pos = cell, vel = Vec2(1f, 0f), damage = 1f, crit = false, lifeRemaining = 5f, friendly = false))
        e.projectiles.add(Projectile(id = 90002, ownerId = RunEngine.PLAYER_ID, pos = cell, vel = Vec2(1f, 0f), damage = 1f, crit = false, lifeRemaining = 5f, friendly = true))
        e.step(1f / 60f, RunInput())
        assertFalse("an enemy shot is stopped by the barricade", e.projectiles.any { it.id == 90001 })
        assertTrue("a friendly shot passes over your own barricade", e.projectiles.any { it.id == 90002 })
    }

    @Test
    fun placementIsRejectedWhenUnaffordableOrInvalid() {
        engine(gold = 5).let { assertFalse(it.placeStructure(StructureType.BARRICADE, nearby(it))) }
        engine(gold = 100).let { assertFalse("can't build on your own cell", it.placeStructure(StructureType.BARRICADE, it.player.pos)) }
        engine(gold = 100).let { assertFalse("can't build outside the arena", it.placeStructure(StructureType.BARRICADE, Vec2(-100f, -100f))) }
    }

    @Test
    fun turretArtifactDeploysAndFiresAutoTurrets() {
        // No gold — the turret is earned as a level-up artifact, not bought. Drive the run and always
        // take the Turret when it's offered, then confirm an auto-turret deploys and shoots.
        val e = engine(gold = 0)
        var deployed = false
        var fired = false
        repeat(9000) {
            when (e.status) {
                RunStatus.LEVEL_UP -> {
                    val opts = e.snapshot().levelUpOptions
                    val pick = opts.firstOrNull { it.modifier.id == Artifacts.TURRET.id } ?: opts.first()
                    e.choose(pick)
                }
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                else -> {}
            }
            e.step(1f / 60f, RunInput())
            if (e.structures.any { it.artifactTurret }) deployed = true
            // Auto-turret projectiles are friendly but owned by the turret, not the player (id 0).
            if (e.projectiles.any { it.friendly && it.ownerId != RunEngine.PLAYER_ID }) fired = true
            if (deployed && fired) return@repeat
        }
        assertTrue("the Turret artifact should deploy an auto-turret", deployed)
        assertTrue("a deployed auto-turret should fire at enemies", fired)
    }

    @Test
    fun turretRankUpsRollAndApplyEquipmentUpgrades() {
        // Always take the Turret; each rank past the first should apply exactly one rolled upgrade.
        val e = engine(gold = 0)
        var frames = 0
        while (frames < 12000) {
            when (e.status) {
                RunStatus.LEVEL_UP -> {
                    val opts = e.snapshot().levelUpOptions
                    val pick = opts.firstOrNull { it.modifier.id == Artifacts.TURRET.id } ?: opts.first()
                    e.choose(pick)
                }
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                else -> {}
            }
            val rank = e.player.held.firstOrNull { it.modifier.id == Artifacts.TURRET.id }?.rank ?: 0
            if (rank >= 2) break
            e.step(1f / 60f, RunInput())
            frames++
        }
        val rank = e.player.held.firstOrNull { it.modifier.id == Artifacts.TURRET.id }?.rank ?: 0
        assertTrue("the turret should have ranked past 1", rank >= 2)
        assertEquals("each rank past the first applies one rolled upgrade", rank - 1, e.player.equipmentUpgrades.size)
    }

    @Test
    fun barricadeCostIsFlat() {
        val e = engine(gold = 200)
        val c0 = e.buildCost(StructureType.BARRICADE)
        assertTrue(e.placeStructure(StructureType.BARRICADE, nearby(e, dx = 2f)))
        assertEquals("placing a barricade no longer inflates the next one", c0, e.buildCost(StructureType.BARRICADE))
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
