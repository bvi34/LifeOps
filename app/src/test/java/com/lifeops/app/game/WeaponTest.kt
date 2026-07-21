package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the magazine/reload mechanic (DESIGN.md §4) and per-weapon range (shot lifetime). */
class WeaponTest {

    private fun config(weapon: StartingWeapon, seed: Long = 7L) = RunConfig(
        weapon = weapon, levelCap = 20, maxHits = 999, startingGold = 0,
        seed = seed, waves = 3, challengeMode = ChallengeMode.NONE,
    )

    /** A fixed aim override makes the weapon fire every cooldown regardless of enemy positions. */
    private val fireInput = RunInput(aimOverride = Vec2(1f, 0f))

    @Test
    fun magazineEmptiesThenAutoReloadsAndRefills() {
        val e = RunEngine(config(StartingWeapon.SHOTGUN)) // 6-round magazine
        assertEquals(6, e.snapshot().ammo)
        var sawEmpty = false
        var sawReloading = false
        var refilledAfterEmpty = false
        repeat(900) { // ~15s: enough to empty, reload, and refill
            e.step(1f / 60f, fireInput)
            val s = e.snapshot()
            if (s.ammo == 0) sawEmpty = true
            if (s.reloading) sawReloading = true
            if (sawEmpty && !s.reloading && s.ammo == e.player.magazineSize) refilledAfterEmpty = true
        }
        assertTrue("firing should empty the magazine", sawEmpty)
        assertTrue("an empty magazine should auto-reload", sawReloading)
        assertTrue("the reload should refill the magazine", refilledAfterEmpty)
    }

    @Test
    fun reloadOnDemandBeginsAReload() {
        val e = RunEngine(config(StartingWeapon.GATLING)) // 60-round magazine
        // Fire a few rounds so the magazine isn't full, then reload on demand.
        repeat(30) { e.step(1f / 60f, fireInput) }
        assertTrue("some rounds should have been spent", e.snapshot().ammo < e.player.magazineSize)
        assertTrue(e.snapshot().ammo > 0)
        e.reload()
        e.step(1f / 60f, RunInput())
        assertTrue("reload-on-demand should put the weapon into a reload", e.snapshot().reloading)
    }

    @Test
    fun reloadIsRejectedOnAFullMagazine() {
        val e = RunEngine(config(StartingWeapon.SNIPER))
        e.reload() // magazine is full at start — nothing to reload
        e.step(1f / 60f, RunInput())
        assertTrue(!e.snapshot().reloading)
    }

    /** Read the lifetime the first friendly shot spawns with — range/speed, or unlimited for Sniper. */
    private fun firstShotLife(weapon: StartingWeapon): Float {
        val e = RunEngine(config(weapon))
        repeat(120) {
            e.step(1f / 60f, fireInput)
            e.projectiles.firstOrNull { it.friendly }?.let { return it.lifeRemaining }
        }
        throw AssertionError("no shot was fired for $weapon")
    }

    @Test
    fun gatlingWindsUpDuringSustainedFire() {
        val e = RunEngine(config(StartingWeapon.GATLING))
        // Fire continuously; record the frame of each shot (ammo ticking down) within the first mag.
        var prevAmmo = e.snapshot().ammo
        val shotFrames = ArrayList<Int>()
        var f = 0
        while (f < 600 && shotFrames.size < 12) {
            e.step(1f / 60f, fireInput); f++
            if (e.snapshot().reloading) break
            val a = e.snapshot().ammo
            if (a < prevAmmo) shotFrames.add(f)
            prevAmmo = a
        }
        assertTrue("need several shots to compare cadence", shotFrames.size >= 6)
        val earlyGap = shotFrames[1] - shotFrames[0]
        val lateGap = shotFrames[shotFrames.size - 1] - shotFrames[shotFrames.size - 2]
        assertTrue("the Gatling fires faster once wound up (early $earlyGap > late $lateGap)", earlyGap > lateGap)
    }

    @Test
    fun gatlingSpinResetsOnReload() {
        val e = RunEngine(config(StartingWeapon.GATLING))
        repeat(180) { e.step(1f / 60f, fireInput) } // ~3s sustained fire → fully wound up
        assertTrue("should have wound up", e.snapshot().spinFrac > 0.5f)
        e.reload()
        e.step(1f / 60f, fireInput)
        assertEquals("reloading spins it back down", 0f, e.snapshot().spinFrac, 0.001f)
    }

    @Test
    fun shotRangeIsShortForShotgunMediumForGatlingUnlimitedForSniper() {
        val shotgun = firstShotLife(StartingWeapon.SHOTGUN)  // 210 / 380 ≈ 0.55s
        val gatling = firstShotLife(StartingWeapon.GATLING)  // 460 / 420 ≈ 1.10s
        val sniper = firstShotLife(StartingWeapon.SNIPER)    // unlimited (never falls short)
        assertTrue("shotgun has the shortest reach", shotgun < gatling)
        assertTrue("gatling is medium range", gatling < sniper)
        assertTrue("sniper shots never expire from travel", sniper >= 3f)
    }
}
