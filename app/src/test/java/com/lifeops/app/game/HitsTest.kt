package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the discrete-hearts model, invulnerability frames, and the ranged enemy. */
class HitsTest {

    private fun config(hits: Int, waves: Int = 99) = RunConfig(
        weapon = StartingWeapon.GATLING, levelCap = 20, maxHits = hits,
        startingGold = 0, seed = 7L, waves = waves, challengeMode = ChallengeMode.NONE,
    )

    @Test
    fun playerLosesOneHeartPerHitThanksToIFrames() {
        // Stationary with only three hearts, and waves far from any boss so contacts are 1-heart.
        val e = RunEngine(config(hits = 3))
        var prev = e.snapshot().playerHits
        var maxDropPerFrame = 0
        var minHits = prev
        var frames = 0
        while (e.status == RunStatus.RUNNING && frames < 3000) {
            e.step(1f / 60f, RunInput())
            frames++
            val h = e.snapshot().playerHits
            maxDropPerFrame = maxOf(maxDropPerFrame, prev - h)
            minHits = minOf(minHits, h)
            prev = h
        }
        assertTrue("the player should actually get hit", minHits < 3)
        assertTrue("invulnerability frames cap it at one heart per hit", maxDropPerFrame <= 1)
    }

    @Test
    fun playerStartsWithTheConfiguredHearts() {
        assertTrue(RunEngine(config(hits = 5)).snapshot().playerHits == 5)
    }

    @Test
    fun enemiesUnlockByTier() {
        assertEquals(0, EnemyType.SHAMBLER.unlockTier)
        assertEquals(0, EnemyType.HUSK.unlockTier)
        assertEquals(1, EnemyType.SPITTER.unlockTier) // shows up at UI "tier 2"
        assertTrue(EnemyType.RUSHER.unlockTier > EnemyType.SPITTER.unlockTier)
        assertTrue(EnemyType.BRUTE.unlockTier > EnemyType.RUSHER.unlockTier)
    }

    @Test
    fun spitterDoesNotSpawnAtTierZero() {
        val e = RunEngine(config(hits = 999, waves = 99)) // never loops → stays tier 0
        var sawSpitter = false
        repeat(1200) {
            e.step(1f / 60f, RunInput())
            if (e.enemies.any { it.type == EnemyType.SPITTER }) sawSpitter = true
        }
        assertFalse("Spitter is a tier-2+ unlock", sawSpitter)
    }

    @Test
    fun spittersAppearAndFireOnceTheRunLoopsIntoHigherTiers() {
        // Invincible, waves = 1 so every clear loops the tier; by tier 1 Spitters are unlocked.
        val e = RunEngine(config(hits = 999, waves = 1))
        var sawEnemyShot = false
        repeat(8000) {
            e.step(1f / 60f, RunInput())
            if (e.snapshot().enemyProjectiles.isNotEmpty()) sawEnemyShot = true
        }
        assertTrue("should have looped past tier 0", e.tier >= 1)
        assertTrue("a ranged enemy should have fired once unlocked", sawEnemyShot)
    }
}
