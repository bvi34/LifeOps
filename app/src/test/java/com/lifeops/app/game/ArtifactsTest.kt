package com.lifeops.app.game

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {

    @Test
    fun baselinePoolIsFourArtifactsEachFourRanks() {
        assertEquals(4, Artifacts.ALL.size)
        assertTrue(Artifacts.ALL.all { it.maxRank == 4 })
    }

    @Test
    fun rankRollUpStacksAdditively() {
        // Overclock: +10% aimed damage per rank; at rank 3 that is +30% on the sniper's base.
        val block = StartingWeapon.SNIPER.baseStatBlock()
        block.addAll(Artifacts.OVERCLOCK.contributionsAt(3))
        val base = StartingWeapon.SNIPER.baseStats[Stat.DAMAGE]!!
        assertEquals(base * 1.30f, block.resolve(Stat.DAMAGE, Scope.AIMED), 0.01f)
    }

    @Test
    fun contributionsAtIsCappedToMaxRank() {
        assertEquals(4, Artifacts.OVERCLOCK.contributionsAt(99).size)
        assertEquals(0, Artifacts.OVERCLOCK.contributionsAt(0).size)
    }

    @Test
    fun splitterAddsWholeProjectiles() {
        val block = StartingWeapon.GATLING.baseStatBlock()
        block.addAll(Artifacts.SPLITTER.contributionsAt(2))
        assertEquals(3f, block.resolve(Stat.PROJECTILES, Scope.AIMED), 0.001f) // base 1 + 2
    }
}
