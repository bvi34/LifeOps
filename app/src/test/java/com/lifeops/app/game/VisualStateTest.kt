package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Covers the presentational state the renderer reads: hit-flash, death bursts, boss health. */
class VisualStateTest {

    private fun cfg(waves: Int = 3, mode: ChallengeMode = ChallengeMode.NONE) = RunConfig(
        weapon = StartingWeapon.GATLING,
        levelCap = 20,
        maxHealth = 4000f,
        startingGold = 0,
        seed = 11L,
        waves = waves,
        challengeMode = mode,
    )

    @Test
    fun enemyFlashesWhenHit() {
        val e = RunEngine(cfg())
        var flashed = false
        repeat(600) {
            e.step(1f / 60f, RunInput())
            if (e.snapshot().enemies.any { it.hitFlashFrac > 0f }) flashed = true
        }
        assertTrue("an enemy should flash on the frame it is hit", flashed)
    }

    @Test
    fun deathBurstsSpawnOnKills() {
        val e = RunEngine(cfg())
        var burst = false
        repeat(900) {
            e.step(1f / 60f, RunInput())
            if (e.snapshot().effects.isNotEmpty()) burst = true
        }
        assertTrue("kills should leave a death-burst effect", burst)
        assertTrue(e.score > 0)
    }

    @Test
    fun bossViewIsPopulatedOnTheFinalWave() {
        // waves = 1 → wave 0 is the final wave, so the boss spawns after its trash.
        val e = RunEngine(cfg(waves = 1))
        var bossSeen = false
        repeat(1200) { i ->
            if (e.status == RunStatus.LEVEL_UP) {
                e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
            e.snapshot().boss?.let { boss ->
                bossSeen = true
                assertTrue(boss.healthFrac in 0f..1f)
            }
        }
        assertTrue("a boss health view should appear on the final wave", bossSeen)
    }

    @Test
    fun everyEnemyViewCarriesAFacingDirection() {
        val e = RunEngine(cfg())
        repeat(400) { e.step(1f / 60f, RunInput()) }
        val views = e.snapshot().enemies
        assertTrue(views.isNotEmpty())
        // Facing points from the enemy toward the player, so it is a (near) unit vector.
        assertTrue(views.all { it.facing.length() <= 1.01f })
    }

    @Test
    fun playerAimIsAUnitVector() {
        val e = RunEngine(cfg())
        repeat(400) { e.step(1f / 60f, RunInput()) }
        val aim = e.snapshot().playerAim
        assertTrue(aim.length() in 0.99f..1.01f)
    }

    @Test
    fun playerMuzzleFlashesWhileFiring() {
        val e = RunEngine(cfg())
        var muzzled = false
        repeat(600) {
            e.step(1f / 60f, RunInput())
            if (e.snapshot().playerMuzzleFrac > 0f) muzzled = true
        }
        assertTrue("the player should flash a muzzle when it fires", muzzled)
    }

    @Test
    fun playerHurtFlashesWhenTouched() {
        val e = RunEngine(cfg())
        var hurt = false
        repeat(900) {
            e.step(1f / 60f, RunInput()) // stationary: enemies reach the player and deal touch damage
            if (e.snapshot().playerHurtFrac > 0f) hurt = true
        }
        assertTrue("the player should flash when taking touch damage", hurt)
    }
}
