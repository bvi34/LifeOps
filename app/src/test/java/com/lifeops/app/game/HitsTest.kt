package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Stat
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
        // Stationary with only three hearts, and waves far from any boss so contacts are 1-heart. A
        // seed whose swarm reliably reaches the (stationary) player; level-up/overflow pauses are
        // resolved so the loop keeps stepping until contact instead of halting on the first pick.
        val e = RunEngine(config(hits = 3).copy(seed = 42L))
        var prev = e.snapshot().playerHits
        var maxDropPerFrame = 0
        var minHits = prev
        var frames = 0
        while (frames < 5000) {
            resolvePauses(e)
            if (e.status == RunStatus.DEFEAT || e.status == RunStatus.VICTORY) break
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
    fun reviveRestoresHeartsAndResumesTheRun() {
        // Stationary with three hearts on the swarm-heavy seed until the pile overruns the player.
        val e = RunEngine(config(hits = 3).copy(seed = 42L))
        var frames = 0
        while (frames < 5000 && e.status != RunStatus.DEFEAT) {
            resolvePauses(e)
            e.step(1f / 60f, RunInput())
            frames++
        }
        assertEquals("the swarm should have overrun the player", RunStatus.DEFEAT, e.status)

        e.revive()
        assertEquals("reviving resumes the same run", RunStatus.RUNNING, e.status)
        assertEquals("revive restores full hearts", e.player.maxHits, e.snapshot().playerHits)
        assertEquals("the revive is counted", 1, e.snapshot().revives)
        assertTrue("revive grants a grace window of invulnerability", e.snapshot().playerInvuln)
    }

    @Test
    fun reviveIsANoOpWhileTheRunIsLive() {
        val e = RunEngine(config(hits = 3))
        e.step(1f / 60f, RunInput())
        e.revive()
        assertEquals("a live run cannot be revived", 0, e.snapshot().revives)
        assertTrue(e.status != RunStatus.DEFEAT)
    }

    @Test
    fun elitesDropMoreOftenThanTrash() {
        assertTrue(EnemyType.HUSK.xpDropChance > EnemyType.SHAMBLER.xpDropChance)
        assertTrue(EnemyType.SPITTER.goldDropChance > EnemyType.RUSHER.goldDropChance)
        assertTrue(EnemyType.BRUTE.xpDropChance > EnemyType.HUSK.xpDropChance)
        // Trash keeps the stingy baseline.
        assertEquals(0.25f, EnemyType.SHAMBLER.xpDropChance, 0.0001f)
        assertEquals(0.25f, EnemyType.RUSHER.xpDropChance, 0.0001f)
    }

    @Test
    fun gatlingBaseNeedsTwoHitsToKillTrash() {
        // Trash is tuned to 20 HP so a single base Gatling shot (16 damage) can't finish it.
        val gatlingDamage = StartingWeapon.GATLING.baseStats[Stat.DAMAGE]!!
        val trashHp = EnemyType.SHAMBLER.maxHealth
        assertEquals(20f, trashHp, 0.001f)
        assertTrue("one base Gatling shot must not kill trash", gatlingDamage < trashHp)
        assertTrue("two base Gatling shots should kill trash", gatlingDamage * 2 >= trashHp)
        // A Sniper still deletes trash in one shot.
        assertTrue("the Sniper one-shots trash", StartingWeapon.SNIPER.baseStats[Stat.DAMAGE]!! >= trashHp)
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
            resolvePauses(e)
            e.step(1f / 60f, RunInput())
            if (e.snapshot().enemyProjectiles.isNotEmpty()) sawEnemyShot = true
        }
        assertTrue("should have looped past tier 0", e.tier >= 1)
        assertTrue("a ranged enemy should have fired once unlocked", sawEnemyShot)
    }

    @Test
    fun eachTierAddsANewBossToTheFinale() {
        // Invincible, waves = 1: tier-0 finale is just the Abomination; the tier-1 finale adds the
        // Spitter Boss on top.
        val e = RunEngine(config(hits = 999, waves = 1))
        var sawSpitterBoss = false
        repeat(9000) {
            resolvePauses(e)
            e.step(1f / 60f, RunInput())
            if (e.enemies.any { it.type == EnemyType.SPITTER_BOSS }) sawSpitterBoss = true
        }
        assertTrue("should have reached the tier-2 finale", e.tier >= 1)
        assertTrue("the tier-2 finale should add the Spitter Boss", sawSpitterBoss)
    }
}
