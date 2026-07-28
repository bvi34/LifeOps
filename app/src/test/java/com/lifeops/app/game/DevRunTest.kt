package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The weekly dev run is the endless engine with two knobs: [RunConfig.maxSets] auto-ends it in
 * victory, and [RunConfig.devRun] marks it a sandbox. These tests pin the engine's only behavioural
 * change (the set ceiling) and the config the dev-run factory hands it.
 */
class DevRunTest {

    /** Drive an invincible player through a bounded run and confirm it wins exactly at the ceiling. */
    @Test
    fun boundedRunEndsInVictoryAtItsSetCeiling() {
        // waves = 1 makes every loop a boss loop; a near-invincible player keeps clearing them.
        val e = RunEngine(
            RunConfig(
                weapon = StartingWeapon.GATLING, levelCap = 30, maxHits = 999,
                startingGold = 0, seed = 5L, waves = 1, challengeMode = ChallengeMode.NONE,
                maxSets = 2,
            )
        )
        var steps = 0
        while (e.status != RunStatus.VICTORY && e.status != RunStatus.DEFEAT && steps < 60_000) {
            resolvePauses(e)
            val ang = steps * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
            steps++
        }
        assertEquals("a bounded run wins once it clears its set ceiling", RunStatus.VICTORY, e.status)
        assertEquals("it ends exactly at the ceiling, never a set beyond", 2, e.tier)
    }

    /** The dev-run factory is a fully-funded sandbox that finishes after DEV_RUN_SETS sets. */
    @Test
    fun devRunConfigIsAMaxedBoundedSandbox() {
        val config = RunConfig.devRun(seed = 1L)
        assertTrue("dev run is flagged as a sandbox", config.devRun)
        assertEquals("dev run ends after the fixed set ceiling", RunConfig.DEV_RUN_SETS, config.maxSets)
        assertEquals("dev run maxes the level cap", RunConfig.MAX_LEVEL_CAP, config.levelCap)
        assertEquals("dev run maxes hearts", RunConfig.MAX_HITS, config.maxHits)
        assertTrue("dev run seeds a starting purse", config.startingGold > 0)
    }

    /** A standard run leaves the ceiling unset, so it stays endless and never auto-wins. */
    @Test
    fun standardRunHasNoSetCeiling() {
        assertNull("the default loadout is endless", RunConfig.default().maxSets)
    }
}
