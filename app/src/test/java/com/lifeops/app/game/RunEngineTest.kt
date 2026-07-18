package com.lifeops.app.game

import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RunEngineTest {

    private fun config(seed: Long = 123L, cap: Int = 20, hp: Float = 500f) = RunConfig(
        weapon = StartingWeapon.GATLING,
        levelCap = cap,
        maxHealth = hp,
        startingGold = 0,
        seed = seed,
        waves = 3,
    )

    /** Step the engine [frames] times at a fixed dt, auto-picking the first level-up option. */
    private fun drive(engine: RunEngine, frames: Int, input: RunInput = RunInput()) {
        repeat(frames) {
            if (engine.status == RunStatus.LEVEL_UP) {
                engine.snapshot().levelUpOptions.firstOrNull()?.let { engine.choose(it) }
            }
            engine.step(1f / 60f, input)
        }
    }

    @Test
    fun startsRunningOnWaveOne() {
        val e = RunEngine(config())
        val s = e.snapshot()
        assertEquals(RunStatus.RUNNING, s.status)
        assertEquals(1, s.wave)
        assertEquals(500f, s.playerHealth, 0.01f)
    }

    @Test
    fun enemiesSpawnAndScoreAccrues() {
        val e = RunEngine(config())
        drive(e, 600) // ~10s
        assertTrue("enemies should have spawned", e.snapshot().enemies.isNotEmpty() || e.score > 0)
    }

    @Test
    fun playerLevelsUpButNeverExceedsCap() {
        // Kite in a circle with a big HP pool so the player reliably survives long enough to farm.
        val e = RunEngine(config(cap = 8, hp = 3000f))
        repeat(1800) { i ->
            if (e.status == RunStatus.LEVEL_UP) {
                e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        assertTrue("should have leveled at least once", e.snapshot().level > 1)
        assertTrue("must respect the funded level cap", e.snapshot().level <= 8)
    }

    @Test
    fun identicalSeedAndInputsAreDeterministic() {
        val a = RunEngine(config(seed = 999L))
        val b = RunEngine(config(seed = 999L))
        drive(a, 900)
        drive(b, 900)
        val sa = a.snapshot()
        val sb = b.snapshot()
        assertEquals(sa.score, sb.score)
        assertEquals(sa.level, sb.level)
        assertEquals(sa.playerHealth, sb.playerHealth, 0.001f)
        assertEquals(sa.enemies.size, sb.enemies.size)
    }

    @Test
    fun stepIsInertAfterRunEnds() {
        // Tiny health, no movement: the player gets overrun and the run ends; further steps no-op.
        val e = RunEngine(config(hp = 5f))
        drive(e, 4000)
        val ended = e.snapshot().status
        assertTrue(ended == RunStatus.DEFEAT || ended == RunStatus.VICTORY)
        val scoreAtEnd = e.snapshot().score
        e.step(1f / 60f, RunInput())
        assertEquals(scoreAtEnd, e.snapshot().score)
    }
}
