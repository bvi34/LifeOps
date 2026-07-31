package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.EventBus
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution
import com.lifeops.app.game.run.Director
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorTest {

    /** A player build with optionally boosted projectiles / aimed damage. */
    private fun playerBlock(extraProjectiles: Int = 0, dmgPercent: Float = 0f): StatBlock {
        val b = StartingWeapon.GATLING.baseStatBlock()
        if (extraProjectiles > 0) {
            b.add(StatContribution(Stat.PROJECTILES, Scope.AIMED, Op.FLAT, extraProjectiles.toFloat()))
        }
        if (dmgPercent > 0f) {
            b.add(StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, dmgPercent))
        }
        return b
    }

    @Test
    fun standardModeLeavesEveryMultiplierAtOne() {
        val d = Director(EventBus())
        d.configure(ChallengeMode.NONE, playerBlock())
        assertEquals(1f, d.spawnMult(), 0.001f)
        assertEquals(1f, d.enemyHpMult(), 0.001f)
        assertEquals(1f, d.enemySpeedMult(), 0.001f)
        assertEquals(1f, d.enemyDamageMult(), 0.001f)
    }

    @Test
    fun mirrorRemapsPlayerGrowthIntoDirectorStats() {
        val d = Director(EventBus())
        // Baseline snapshot at run start: no growth yet, so multipliers stay at 1.
        d.configure(ChallengeMode.MIRROR, playerBlock())
        assertEquals(1f, d.spawnMult(), 0.001f)

        // Player doubles projectiles (1→2, +100%) and gains +50% aimed damage.
        d.rebuild(playerBlock(extraProjectiles = 1, dmgPercent = 0.5f))
        // projectiles: growth 1.0 × factor 0.6 → spawnMult 1.6
        assertEquals(1.6f, d.spawnMult(), 0.01f)
        // damage: growth 0.5 × factor 0.8 → enemyHpMult 1.4
        assertEquals(1.4f, d.enemyHpMult(), 0.01f)
        // fire rate unchanged → enemy speed untouched
        assertEquals(1f, d.enemySpeedMult(), 0.001f)
    }

    @Test
    fun mobBossAttachesArtifactsToEnemiesThroughTheSharedPath() {
        assertTrue(ChallengeMode.MOB_BOSS.enemyModifiers.isNotEmpty())
        // The attached artifact (Adrenaline, GLOBAL move speed) buffs an enemy's own stat block —
        // the same StatBlock code path the player uses.
        val base = StatBlock(mapOf(Stat.MOVE_SPEED to 50f))
        val boosted = StatBlock(mapOf(Stat.MOVE_SPEED to 50f))
        ChallengeMode.MOB_BOSS.enemyModifiers.forEach { boosted.addAll(it.contributions()) }
        assertTrue(boosted.resolve(Stat.MOVE_SPEED) > base.resolve(Stat.MOVE_SPEED))
    }

    @Test
    fun mobBossEnemiesActuallyMoveFasterInEngine() {
        fun firstEnemySpeed(mode: ChallengeMode): Float {
            val e = RunEngine(
                RunConfig(StartingWeapon.GATLING, levelCap = 20, maxHits = 99, startingGold = 0, seed = 5L, waves = 3, challengeMode = mode)
            )
            // Step until the first enemy appears.
            repeat(300) { if (e.enemies.isEmpty()) e.step(1f / 60f, RunInput()) }
            return e.enemies.first().moveSpeed
        }
        assertTrue(firstEnemySpeed(ChallengeMode.MOB_BOSS) > firstEnemySpeed(ChallengeMode.NONE))
    }

    @Test
    fun challengeRunStaysDeterministic() {
        fun run() = RunEngine(
            RunConfig(StartingWeapon.GATLING, levelCap = 20, maxHits = 99, startingGold = 0, seed = 77L, waves = 3, challengeMode = ChallengeMode.MIRROR)
        ).also { e -> repeat(600) { e.step(1f / 60f, RunInput()) } }
        assertEquals(run().snapshot().score, run().snapshot().score)
    }
}
