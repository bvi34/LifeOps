package com.lifeops.app.game

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.content.SetBonuses
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.TempBoosts
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.OverflowKind
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.chooseOverflow
import com.lifeops.app.game.run.chooseSetBonus
import com.lifeops.app.game.run.skipStore
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-set boon/bane draft (DESIGN.md §7) and the overflow micro-pick (§6). */
class SetBonusTest {

    private fun circling(config: RunConfig, frames: Int, stopOn: RunStatus, pickTempBoost: Boolean = true): RunEngine {
        val e = RunEngine(config)
        for (i in 0 until frames) {
            if (e.status == stopOn) return e
            // Resolve any *other* pause so the run keeps advancing toward [stopOn].
            when (e.status) {
                RunStatus.LEVEL_UP -> e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                RunStatus.SET_BONUS -> if (stopOn != RunStatus.SET_BONUS)
                    e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                RunStatus.STORE -> if (stopOn != RunStatus.STORE) e.skipStore()
                RunStatus.OVERFLOW -> if (stopOn != RunStatus.OVERFLOW) {
                    val opts = e.snapshot().overflowOptions
                    val pick = if (pickTempBoost) opts.first() else opts.first { it.kind == OverflowKind.GOLD }
                    e.chooseOverflow(pick)
                }
                else -> {}
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        return e
    }

    @Test
    fun poolsPairPlayerBoonsWithEnemyBanes() {
        assertTrue(SetBonuses.BOONS.size >= 3)
        assertTrue(SetBonuses.BANES.isNotEmpty())
        // Every bane targets a Director-only stat, so it bites the enemies, not the player.
        assertTrue(SetBonuses.BANES.all { it.contribution.stat.name.startsWith("ENEMY_") || it.contribution.stat.name == "SPAWN_MULT" })
    }

    @Test
    fun clearingASetOffersABoonAndBaneDraft() {
        // Invincible, waves = 1 so the first boss clear immediately loops into set 2 and drafts.
        val e = circling(
            RunConfig(StartingWeapon.GATLING, levelCap = 30, maxHits = 999, startingGold = 0, seed = 5L, waves = 1),
            frames = 8000, stopOn = RunStatus.SET_BONUS,
        )
        assertEquals("clearing the set should pause for the draft", RunStatus.SET_BONUS, e.status)
        val options = e.snapshot().setBonusOptions
        assertTrue("a set draft should offer choices", options.isNotEmpty())
        options.forEach {
            assertNotNull(it.boon)
            assertNotNull(it.bane)
        }
        assertTrue("set (tier) should have incremented before the draft", e.tier >= 1)

        // Choosing the draft grants the boon and hosts the bane; the store then opens between sets.
        val chosen = options.first()
        e.chooseSetBonus(chosen)
        assertEquals("the boon/bane draft is followed by the store", RunStatus.STORE, e.status)
        assertTrue("the store should offer up to four picks", e.snapshot().storeOptions.isNotEmpty())
        // Skipping the store rolls straight into the next set.
        e.skipStore()
        assertEquals(RunStatus.RUNNING, e.status)
        assertTrue("the drafted boon should show on the HUD", e.snapshot().boons.contains(chosen.boon.name))
    }

    @Test
    fun overflowOffersGoldHealAndTempBoost() {
        // A tiny cap so the moving farmer quickly caps out and starts overflowing.
        val e = circling(
            RunConfig(StartingWeapon.GATLING, levelCap = RunConfig.MIN_LEVEL_CAP, maxHits = 999, startingGold = 0, seed = 5L, waves = 7),
            frames = 8000, stopOn = RunStatus.OVERFLOW,
        )
        assertEquals("overflow past the cap should pause for a micro-pick", RunStatus.OVERFLOW, e.status)
        val kinds = e.snapshot().overflowOptions.map { it.kind }.toSet()
        assertEquals(setOf(OverflowKind.GOLD, OverflowKind.HEAL, OverflowKind.TEMP_BOOST), kinds)

        val goldBefore = e.snapshot().gold
        e.chooseOverflow(e.snapshot().overflowOptions.first { it.kind == OverflowKind.GOLD })
        assertEquals(RunStatus.RUNNING, e.status)
        assertTrue("bonus gold should have been granted", e.snapshot().gold > goldBefore)
    }

    @Test
    fun tempBoostAppliesThenExpires() {
        val e = circling(
            RunConfig(StartingWeapon.GATLING, levelCap = RunConfig.MIN_LEVEL_CAP, maxHits = 999, startingGold = 0, seed = 5L, waves = 7),
            frames = 8000, stopOn = RunStatus.OVERFLOW,
        )
        assertEquals(RunStatus.OVERFLOW, e.status)
        e.chooseOverflow(e.snapshot().overflowOptions.first { it.kind == OverflowKind.TEMP_BOOST })
        assertTrue("a temp surge should be active right after the pick", e.snapshot().tempBuffs.isNotEmpty())

        // Idle past the surge duration, only ever taking non-temp overflow picks, so it lapses.
        val holdFrames = (TempBoosts.DURATION_SECONDS * 60).toInt() + 120
        repeat(holdFrames) {
            when (e.status) {
                RunStatus.LEVEL_UP -> e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                RunStatus.STORE -> e.skipStore()
                RunStatus.OVERFLOW -> e.chooseOverflow(e.snapshot().overflowOptions.first { it.kind == OverflowKind.GOLD })
                else -> {}
            }
            e.step(1f / 60f, RunInput())
        }
        assertTrue("the temp surge should have expired after its duration", e.snapshot().tempBuffs.isEmpty())
    }
}
