package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
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
    fun spittersFireEnemyProjectiles() {
        // Plenty of hearts so the run keeps going; Spitters appear and open fire within range.
        val e = RunEngine(config(hits = 999))
        var sawEnemyShot = false
        repeat(1200) {
            e.step(1f / 60f, RunInput())
            if (e.snapshot().enemyProjectiles.isNotEmpty()) sawEnemyShot = true
        }
        assertTrue("a ranged enemy should have fired", sawEnemyShot)
    }
}
