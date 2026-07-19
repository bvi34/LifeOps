package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.Tiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EndlessTest {

    @Test
    fun tiersScaleUpAndSpeedIsCapped() {
        assertEquals(1f, Tiers.hpMult(0), 0.001f)
        assertTrue(Tiers.hpMult(3) > Tiers.hpMult(0))
        assertTrue(Tiers.damageMult(5) > Tiers.damageMult(1))
        assertTrue("speed escalation stays readable", Tiers.speedMult(100) <= 1.5f + 0.001f)
    }

    @Test
    fun runLoopsPastTheBossIntoHigherTiersAndNeverWins() {
        // A near-invincible player so the run keeps looping; waves = 1 makes every loop a boss loop.
        val e = RunEngine(
            RunConfig(
                weapon = StartingWeapon.GATLING, levelCap = 30, maxHits = 999,
                startingGold = 0, seed = 5L, waves = 1, challengeMode = ChallengeMode.NONE,
            )
        )
        repeat(6000) { i ->
            if (e.status == RunStatus.LEVEL_UP) {
                e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        assertTrue("clearing the boss should loop into a higher tier", e.tier >= 1)
        assertNotEquals("endless mode never ends in victory", RunStatus.VICTORY, e.status)
    }
}
