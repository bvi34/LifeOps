package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Enemy
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Set-4 spawner mechanic: the stationary Nest (trash) and the fleeing Mother (boss) both birth
 * minions on the field while they live. The engine is JVM-pure and seeded, so these run headless and
 * deterministically — we inject a spawner into [RunEngine.enemies] and step to watch it act.
 */
class SpawnerTest {

    private fun engine(waves: Int = 99, seed: Long = 11L) = RunEngine(
        RunConfig(
            weapon = StartingWeapon.GATLING, levelCap = 20, maxHits = 999,
            startingGold = 0, seed = seed, waves = waves, challengeMode = ChallengeMode.NONE,
        )
    )

    @Test
    fun spawnerArchetypesUnlockAtSetFour() {
        // Set 4 is internal tier 3 — the HUD shows tier + 1.
        assertEquals(3, EnemyType.NEST.unlockTier)
        assertEquals(3, EnemyType.MOTHER.unlockTier)
        assertTrue(EnemyType.NEST.isSpawner)
        assertTrue(EnemyType.MOTHER.isSpawner)

        // The Nest is a stationary trash sac that hatches plain Shamblers and never flees or accelerates.
        assertFalse(EnemyType.NEST.isBoss)
        assertEquals(0f, EnemyType.NEST.moveSpeed, 0.0001f)
        assertFalse(EnemyType.NEST.broodRandom)
        assertFalse(EnemyType.NEST.fleesPlayer)
        assertFalse(EnemyType.NEST.broodResetsOnDeath)

        // The Mother is a fleeing boss that births random enemies and speeds up as her brood dies.
        assertTrue(EnemyType.MOTHER.isBoss)
        assertTrue(EnemyType.MOTHER.fleesPlayer)
        assertTrue(EnemyType.MOTHER.broodRandom)
        assertTrue(EnemyType.MOTHER.broodResetsOnDeath)
        // She births faster than the Nest hatches.
        assertTrue(EnemyType.MOTHER.spawnInterval < EnemyType.NEST.spawnInterval)
    }

    @Test
    fun brokeNothingRemovesTheOldBrute() {
        // The Brute was swapped out for the spawners; it must no longer exist as an archetype.
        assertTrue(EnemyType.values().none { it.name == "BRUTE" })
    }

    @Test
    fun aNestHatchesShamblersWhileItLives() {
        val e = engine()
        // Park an effectively-invincible Nest at an interior corner of the arena (well inside the
        // bounds so it isn't nudged by edge-clamping) — it will hatch Shamblers beside itself on its
        // cadence while sitting perfectly still. The huge HP keeps the player's auto-fire from cracking
        // it during the test window.
        val nestPos = Vec2(260f, 220f)
        val nest = Enemy(
            id = 900_000, type = EnemyType.NEST, pos = nestPos,
            health = 1_000_000f, maxHealth = 1_000_000f, moveSpeed = 0f,
            spawnCooldown = EnemyType.NEST.spawnInterval,
        )
        e.enemies.add(nest)

        var hatched = false
        var frames = 0
        while (frames < 900 && !hatched) {
            resolvePauses(e)
            if (e.status != RunStatus.RUNNING) break
            e.step(1f / 60f, RunInput())
            hatched = hatched || e.enemies.any { it.parentId == nest.id && it.type == EnemyType.SHAMBLER }
            frames++
        }
        assertTrue("a living Nest should hatch a Shambler within a few cadences", hatched)
        // The Nest itself never moves — it sits where it landed.
        assertEquals(nestPos.x, nest.pos.x, 0.001f)
        assertEquals(nestPos.y, nest.pos.y, 0.001f)
    }

    @Test
    fun aMotherFleesThePlayerAndBirthsBrood() {
        val e = engine()
        val playerStart = e.snapshot().playerPos
        val mother = Enemy(
            id = 900_001, type = EnemyType.MOTHER,
            pos = Vec2(playerStart.x + 30f, playerStart.y), // start right on top of the player
            health = 1_000_000f, maxHealth = 1_000_000f,
            moveSpeed = EnemyType.MOTHER.moveSpeed,
            spawnCooldown = EnemyType.MOTHER.spawnInterval,
        )
        e.enemies.add(mother)
        val startDist = mother.pos.distanceTo(playerStart)

        var births = false
        var frames = 0
        while (frames < 400) {
            resolvePauses(e)
            if (e.status != RunStatus.RUNNING) break
            e.step(1f / 60f, RunInput())
            births = births || e.enemies.any { it.parentId == mother.id }
            frames++
        }
        assertTrue(
            "the Mother should flee, ending farther from the player than she started",
            mother.pos.distanceTo(e.snapshot().playerPos) > startDist,
        )
        assertTrue("the Mother should birth brood while she lives", births)
    }
}
