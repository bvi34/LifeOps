package com.lifeops.app.game

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.ArtifactCategory
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactsTest {

    @Test
    fun poolSplitsIntoStatSupportAndCombatEquipment() {
        // Four stat-support artifacts (4 ranks each) + the Turret combat-equipment artifact.
        assertEquals(4, Artifacts.STAT_SUPPORT.size)
        assertTrue(Artifacts.STAT_SUPPORT.all { it.maxRank == 4 })
        assertEquals(1, Artifacts.COMBAT_EQUIPMENT.size)
        assertEquals(Artifacts.STAT_SUPPORT + Artifacts.COMBAT_EQUIPMENT, Artifacts.ALL)
        assertEquals(ArtifactCategory.COMBAT_EQUIPMENT, Artifacts.TURRET.category)
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

    @Test
    fun autoloaderRaisesReloadSpeed() {
        // Autoloader now shortens reloads: +20% reload speed per rank (DESIGN.md §4).
        val block = StartingWeapon.GATLING.baseStatBlock()
        block.addAll(Artifacts.AUTOLOADER.contributionsAt(2))
        assertEquals(1.40f, block.resolve(Stat.RELOAD_SPEED, Scope.AIMED), 0.001f) // base 1.0 + 40%
    }

    @Test
    fun turretArtifactGrantsOneTurretAndRanksToFour() {
        // Rank 1 deploys one turret; ranks 2-4 add rolled upgrades (applied by the engine), not fixed
        // rows — so the modifier alone only ever contributes the single base turret on the AUTO scope.
        assertEquals(4, Artifacts.TURRET.maxRank)
        val block = StartingWeapon.SNIPER.baseStatBlock()
        block.addAll(Artifacts.TURRET.contributionsAt(4))
        assertEquals(1f, block.resolve(Stat.TURRET_COUNT, Scope.AUTO), 0.001f)
        // Turret count is an AUTO stat — it does not bleed into the player's aimed weapon.
        assertEquals(0f, block.resolve(Stat.TURRET_COUNT, Scope.AIMED), 0.001f)
    }

    @Test
    fun everyTurretUpgradeRollIsAutoScoped() {
        // The rolled pool only ever lifts turret (AUTO) stats, never the aimed weapon.
        assertTrue(com.lifeops.app.game.content.TurretUpgrades.POOL.isNotEmpty())
        assertTrue(com.lifeops.app.game.content.TurretUpgrades.POOL.all { it.contribution.scope == Scope.AUTO })
    }
}
