package com.lifeops.app.game

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.EquipmentUpgrades
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.ArtifactCategory
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatContribution
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Enemy
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.Structure
import com.lifeops.app.game.run.TempBuff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Outpost equipment (DESIGN.md §9): a *shop*-unlocked emplacement that plants permanent Sentry +
 * Barricade pairs near the player, distinct from the always-draftable Turret. Its ranks-2+ pool buffs
 * the whole defensive line globally — sentry damage / fire rate and barricade thorns lift every static
 * Sentry / Barricade you own, gold-built ones included.
 */
class OutpostTest {

    private fun engine(gold: Int = 0): RunEngine =
        RunEngine(RunConfig(weapon = StartingWeapon.GATLING, levelCap = 20, maxHits = 999,
            startingGold = gold, seed = 3L, waves = 7))
            .also { it.player.reloadRemaining = 999f } // mute the aimed gun so it never confuses results

    /** A cell a couple of tiles from the player (inside the arena, not the player's own cell). */
    private fun nearby(e: RunEngine, dx: Float = 1f, dy: Float = 0f): Vec2 =
        e.player.pos + Vec2(e.arena.cellSize * dx, e.arena.cellSize * dy)

    /**
     * Fold [buff] into the live build. A stat rebuild only fires on a handful of events; the cheapest
     * from a test is a temp buff expiring — so we add the real buff to [runBonuses] and a throwaway
     * zero buff that expires on the next step, whose pruning triggers the rebuild that folds ours in.
     */
    private fun forceBuff(e: RunEngine, buff: StatContribution) {
        e.player.runBonuses.add(buff)
        e.player.tempBuffs.add(TempBuff(StatContribution(Stat.XP_GAIN, Scope.GLOBAL, Op.FLAT, 0f), "noop", 0f))
        e.step(1f / 60f, RunInput())
    }

    // --- Data / wiring -------------------------------------------------------------------------

    @Test
    fun outpostIsTheEquipmentShopUnlock() {
        val item = StoreCatalog.byId("outpost") as StoreCatalog.Item.ArtifactItem
        assertEquals(StoreCatalog.Category.EQUIPMENT, item.category)
        assertEquals(ArtifactCategory.COMBAT_EQUIPMENT, item.modifier.category)
        assertEquals(4, item.modifier.maxRank)
        // Rank 1 grants exactly one outpost; the engine keeps that many emplacements planted.
        val block = StartingWeapon.GATLING.baseStatBlock()
        block.addAll(item.modifier.contributionsAt(4))
        assertEquals(1f, block.resolve(Stat.OUTPOST_COUNT), 0.001f)
    }

    @Test
    fun outpostUpgradesBuffTheWholeDefensiveLineGlobally() {
        val pool = EquipmentUpgrades.OUTPOST
        assertTrue("outpost has a rolled upgrade pool", pool.isNotEmpty())
        // Line-wide buffs read by sentries/barricades whoever placed them → GLOBAL, never AUTO/AIMED.
        assertTrue("outpost upgrades are global", pool.all { it.contribution.scope == Scope.GLOBAL })
        val stats = pool.map { it.contribution.stat }.toSet()
        val allowed = setOf(Stat.OUTPOST_COUNT, Stat.SENTRY_DAMAGE, Stat.SENTRY_FIRE_RATE, Stat.BARRICADE_THORNS)
        assertTrue("outpost upgrades touch only outpost/sentry/barricade stats", stats.all { it in allowed })
        assertEquals(pool, EquipmentUpgrades.poolFor("outpost"))
    }

    @Test
    fun outpostAndTurretPoolsNeverBleedIntoEachOther() {
        val outpostStats = EquipmentUpgrades.OUTPOST.map { it.contribution.stat }.toSet()
        val turretStats = EquipmentUpgrades.TURRET.map { it.contribution.stat }.toSet()
        assertTrue("outpost and turret upgrades touch disjoint stats",
            outpostStats.intersect(turretStats).isEmpty())
        // The old "sentry_array" id no longer resolves to the turret pool (renamed to the Outpost).
        assertTrue(EquipmentUpgrades.poolFor("sentry_array").isEmpty())
    }

    @Test
    fun theTurretStaysFreelyDraftableAlongsideTheOutpost() {
        // The Outpost is a *shop* unlock; the Turret remains in the baseline level-up pool with no
        // unlock required — buying the Outpost never gated the turret behind the shop.
        assertTrue("the Turret artifact is in the baseline draft pool",
            Artifacts.ALL.any { it.id == "turret" })
    }

    // --- Engine behaviour ----------------------------------------------------------------------

    @Test
    fun outpostPlantsAPermanentSentryAndBarricade() {
        val e = engine()
        e.enemies.clear()
        forceBuff(e, StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 1f))
        // The emplacement drips in on the next eligible frame (the deploy timer starts ready).
        repeat(10) { e.step(1f / 60f, RunInput()); e.enemies.clear() }
        val sentry = e.structures.firstOrNull { it.fromOutpost && it.type == StructureType.SENTRY }
        val barricade = e.structures.firstOrNull { it.fromOutpost && it.type == StructureType.BARRICADE }
        assertNotNull("the Outpost should plant a Sentry", sentry)
        assertNotNull("the Outpost should wall in a Barricade alongside the Sentry", barricade)
        // Permanent — unlike the auto-turret they carry no finite TTL.
        assertEquals(Float.POSITIVE_INFINITY, sentry!!.ttl, 0f)
        assertEquals(Float.POSITIVE_INFINITY, barricade!!.ttl, 0f)
    }

    @Test
    fun outpostCountIsMaintainedIndependentlyOfGoldBuiltSentries() {
        // A gold Sentry must not count toward the Outpost's target, or it would suppress deployment.
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.SENTRY, nearby(e, dx = 3f)))
        e.enemies.clear()
        forceBuff(e, StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 1f))
        repeat(10) { e.step(1f / 60f, RunInput()); e.enemies.clear() }
        assertTrue("the Outpost still plants its own Sentry despite a gold one being present",
            e.structures.any { it.fromOutpost && it.type == StructureType.SENTRY })
    }

    @Test
    fun sentryDamageUpgradeLiftsEveryStaticSentry() {
        // Two identical setups; only the buffed one carries the line-wide sentry-damage upgrade. The
        // buffed Sentry should wear the same enemy down further over the same window.
        val plain = sentryVsEnemy(buff = null)
        val buffed = sentryVsEnemy(buff = StatContribution(Stat.SENTRY_DAMAGE, Scope.GLOBAL, Op.ADD_PERCENT, 4f))
        repeat(300) {
            plain.step(1f / 60f, RunInput()); plain.enemies.retainAll { it.id == TARGET_ID }
            buffed.step(1f / 60f, RunInput()); buffed.enemies.retainAll { it.id == TARGET_ID }
        }
        assertTrue("a buffed Sentry deals more damage to the same enemy over the same window",
            buffed.enemies.first().health < plain.enemies.first().health)
    }

    /** A muted engine holding one static gold-style Sentry with a fat-HP enemy parked in its range. */
    private fun sentryVsEnemy(buff: StatContribution?): RunEngine {
        val e = engine()
        if (buff != null) forceBuff(e, buff)
        e.enemies.clear(); e.structures.clear()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.structures.add(Structure(id = 95001, type = StructureType.SENTRY, col = 0, row = 0,
            pos = Vec2(px + 120f, py), hp = StructureType.SENTRY.maxHp, maxHp = StructureType.SENTRY.maxHp))
        // Parked (moveSpeed 0) just 60 units from the Sentry, well inside its 260 range, with huge HP
        // so neither engine's Sentry can finish it in the window — we compare remaining health.
        e.enemies.add(Enemy(id = TARGET_ID, type = EnemyType.SHAMBLER, pos = Vec2(px + 180f, py),
            health = 100_000f, maxHealth = 100_000f, moveSpeed = 0f))
        return e
    }

    @Test
    fun barricadeThornsBiteEnemiesThatStrikeIt() {
        // With the aimed gun muted and no other damage source on the field, a barricade's thorns are
        // the *only* way an enemy can die — so any kill (score > 0) proves the wall bit back.
        val e = engine(gold = 100)
        assertTrue(e.placeStructure(StructureType.BARRICADE, nearby(e, dx = 1f)))
        forceBuff(e, StatContribution(Stat.BARRICADE_THORNS, Scope.GLOBAL, Op.FLAT, 500f))
        repeat(2000) { e.step(1f / 60f, RunInput()) }
        assertTrue("with the gun muted, only barricade thorns can kill — a kill proves thorns bite",
            e.score > 0L)
    }

    companion object {
        private const val TARGET_ID = 95002
    }
}
