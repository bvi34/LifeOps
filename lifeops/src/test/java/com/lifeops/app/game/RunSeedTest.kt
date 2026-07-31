package com.lifeops.app.game

import com.lifeops.app.game.core.RunSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSeedTest {

    @Test
    fun sameSeedProducesSameSequence() {
        val a = RunSeed(42L)
        val b = RunSeed(42L)
        repeat(100) { assertEquals(a.nextFloat(), b.nextFloat(), 0f) }
    }

    @Test
    fun differentWeeksProduceDifferentSeeds() {
        val w1 = RunSeed.fromWeek("2026-07-13")
        val w2 = RunSeed.fromWeek("2026-07-20")
        assertNotEquals(w1, w2)
    }

    @Test
    fun weekSeedIsStable() {
        assertEquals(RunSeed.fromWeek("2026-07-13"), RunSeed.fromWeek("2026-07-13"))
    }

    @Test
    fun floatsAreInUnitInterval() {
        val r = RunSeed(7L)
        repeat(1000) {
            val f = r.nextFloat()
            assertTrue(f >= 0f && f < 1f)
        }
    }

    @Test
    fun intBoundsAreRespected() {
        val r = RunSeed(9L)
        repeat(1000) {
            val v = r.nextInt(4)
            assertTrue(v in 0..3)
        }
    }
}
