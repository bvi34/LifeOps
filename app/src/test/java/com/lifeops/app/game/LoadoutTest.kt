package com.lifeops.app.game

import com.lifeops.app.data.model.GameResource
import com.lifeops.app.game.run.Loadout
import com.lifeops.app.game.run.RunConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadoutTest {

    private fun res(name: String, value: Int, slot: Int) =
        GameResource(id = name, name = name, currentValue = value, lifetimeEarned = value, slotIndex = slot)

    private val defaultSlots = listOf(
        res("Gold", 50, 0),
        res("Energy", 25, 1),
        res("Wisdom", 30, 2),
        res("Strength", 40, 3),
        res("Spirit", 10, 4),
    )

    @Test
    fun energyResolvesByNameAndGatesEntry() {
        assertNotNull(Loadout.energyResource(defaultSlots))
        assertTrue(Loadout.canAfford(defaultSlots))
    }

    @Test
    fun lockedOutWhenEnergyBelowCost() {
        val poor = listOf(res("Energy", Loadout.ENERGY_COST - 1, 1))
        assertFalse(Loadout.canAfford(poor))
    }

    @Test
    fun rolesFallBackToSlotIndexWhenNamesDoNotMatch() {
        val opaque = listOf(
            res("Alpha", 10, 0),
            res("Bravo", 10, 1),
            res("Charlie", 10, 2),
            res("Delta", 10, 3),
        )
        // No keyword hits → slot-index fallback: gold=0, energy=1, levelCap=2, maxHealth=3.
        assertEquals("Bravo", Loadout.energyResource(opaque)?.name)
        assertEquals("Charlie", Loadout.resolve(Loadout.Role.LEVEL_CAP, opaque)?.name)
        assertEquals("Delta", Loadout.resolve(Loadout.Role.MAX_HEALTH, opaque)?.name)
        assertEquals("Alpha", Loadout.resolve(Loadout.Role.STARTING_GOLD, opaque)?.name)
    }

    @Test
    fun committedUnitsConvertWithinBounds() {
        assertEquals(Loadout.BASE_LEVEL_CAP + 3, Loadout.levelCapFor(30))
        assertEquals(RunConfig.MAX_LEVEL_CAP, Loadout.levelCapFor(100_000))
        // Hearts: baseline 3, +1 per UNITS_PER_HEART banked, capped.
        assertEquals(RunConfig.BASE_HITS, Loadout.heartsFor(0))
        assertEquals(RunConfig.BASE_HITS + 2, Loadout.heartsFor(2 * Loadout.UNITS_PER_HEART))
        assertEquals(RunConfig.MAX_HITS, Loadout.heartsFor(100_000))
    }

    @Test
    fun maxUsefulCommitmentsReachTheStatCeilingExactly() {
        // Committing exactly the "max useful" amount hits the stat ceiling; a step less falls short,
        // so nothing beyond these thresholds can raise the stat (and must not be spent for no gain).
        assertEquals(RunConfig.MAX_HITS, Loadout.heartsFor(Loadout.MAX_USEFUL_HEALTH))
        assertEquals(RunConfig.MAX_HITS - 1, Loadout.heartsFor(Loadout.MAX_USEFUL_HEALTH - Loadout.UNITS_PER_HEART))
        assertEquals(RunConfig.MAX_LEVEL_CAP, Loadout.levelCapFor(Loadout.MAX_USEFUL_LEVEL_CAP))
        assertEquals(RunConfig.MAX_LEVEL_CAP - 1, Loadout.levelCapFor(Loadout.MAX_USEFUL_LEVEL_CAP - Loadout.UNITS_PER_LEVEL_CAP))
    }
}
