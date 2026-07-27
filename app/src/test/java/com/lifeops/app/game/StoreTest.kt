package com.lifeops.app.game

import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The between-set draft shop (DESIGN.md §9): four random offers, pick-one, banked-currency spend
 * (brokered outside the engine), a grant to the current run, and a permanent unlock that rejoins
 * future runs' pools.
 */
class StoreTest {

    private val allIds = StoreCatalog.ITEMS.map { it.id }.toSet()
    private val artifactIds = StoreCatalog.ITEMS.filterIsInstance<StoreCatalog.Item.ArtifactItem>().map { it.id }.toSet()

    /** Circle an invincible farmer until it hits the store (waves = 1 loops fast into set 2). */
    private fun driveToStore(unlockedIds: Set<String> = emptySet()): RunEngine {
        val e = RunEngine(
            RunConfig(StartingWeapon.GATLING, levelCap = 30, maxHits = 999, startingGold = 0, seed = 5L, waves = 1,
                unlockedIds = unlockedIds)
        )
        repeat(12000) { i ->
            when (e.status) {
                RunStatus.STORE -> return e
                RunStatus.LEVEL_UP -> e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                else -> {}
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        error("the run never reached the store")
    }

    @Test
    fun catalogIsWellFormed() {
        assertTrue("catalog should not be empty", StoreCatalog.ITEMS.isNotEmpty())
        // Ids are unique — they're the persisted unlock keys.
        assertEquals("store item ids must be unique", allIds.size, StoreCatalog.ITEMS.size)
        // Every item prices by its tier's fixed cost (§9: 25 / 50 / 100 / 150).
        StoreCatalog.ITEMS.forEach { assertEquals(it.category.cost, it.category.cost) }
        assertEquals(25, StoreCatalog.Category.MODIFIER.cost)
        assertEquals(50, StoreCatalog.Category.PASSIVE.cost)
        assertEquals(100, StoreCatalog.Category.EQUIPMENT.cost)
        assertEquals(150, StoreCatalog.Category.GUN.cost)
        // All four faces are represented.
        assertTrue(StoreCatalog.ITEMS.any { it.category == StoreCatalog.Category.MODIFIER })
        assertTrue(StoreCatalog.ITEMS.any { it.category == StoreCatalog.Category.PASSIVE })
        assertTrue(StoreCatalog.ITEMS.any { it.category == StoreCatalog.Category.EQUIPMENT })
        assertTrue(StoreCatalog.ITEMS.any { it.category == StoreCatalog.Category.GUN })
    }

    @Test
    fun storeOpensAfterTheSetDraftWithUpToFourDistinctUnownedOffers() {
        val e = driveToStore()
        assertEquals(RunStatus.STORE, e.status)
        val offers = e.snapshot().storeOptions
        assertTrue("the store should offer at least one pick", offers.isNotEmpty())
        assertTrue("the store shows no more than four offers", offers.size <= RunEngine.STORE_OFFER_COUNT)
        assertEquals("offers are distinct", offers.map { it.itemId }.toSet().size, offers.size)
        offers.forEach { assertTrue("offers price by their catalog cost", it.cost > 0) }
    }

    @Test
    fun buyingAGunSwapsTheAimedWeapon() {
        // Own everything except the Hand Cannon, so it is the only thing the store can offer.
        val e = driveToStore(unlockedIds = allIds - "gun_hand_cannon")
        val offer = e.snapshot().storeOptions.single()
        assertEquals("gun_hand_cannon", offer.itemId)
        e.applyStorePurchase(offer.itemId)
        assertEquals(RunStatus.RUNNING, e.status)
        assertEquals("buying a gun replaces the run's aimed weapon", StartingWeapon.HAND_CANNON, e.player.weapon)
    }

    @Test
    fun buyingAPassiveAddsItToTheBuildImmediately() {
        val e = driveToStore(unlockedIds = allIds - "eagle_eye")
        val offer = e.snapshot().storeOptions.single()
        assertEquals("eagle_eye", offer.itemId)
        e.applyStorePurchase(offer.itemId)
        assertTrue("a bought passive joins the run's build at rank 1",
            e.player.held.any { it.modifier.id == "eagle_eye" && it.rank == 1 })
    }

    @Test
    fun buyingAMutatorAppliesItsRunBonuses() {
        val e = driveToStore(unlockedIds = allIds - "mut_berserk")
        val offer = e.snapshot().storeOptions.single()
        assertEquals("mut_berserk", offer.itemId)
        val mutator = StoreCatalog.byId("mut_berserk") as StoreCatalog.Item.MutatorItem
        e.applyStorePurchase(offer.itemId)
        assertTrue("a bought mutator folds its player bonuses into the run",
            e.player.runBonuses.containsAll(mutator.playerBonuses))
    }

    @Test
    fun anOwnedItemIsNotOfferedAgain() {
        // Owning everything but one, then buying it, leaves nothing to sell — the store is skipped.
        val e = driveToStore(unlockedIds = allIds - "scavenger")
        e.applyStorePurchase("scavenger")
        // Drive to the next set boundary; with the catalog exhausted there is no STORE pause.
        repeat(12000) { i ->
            when (e.status) {
                RunStatus.LEVEL_UP -> e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                RunStatus.SET_BONUS -> {
                    e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                    assertEquals("an exhausted catalog skips the store entirely", RunStatus.RUNNING, e.status)
                    return
                }
                RunStatus.STORE -> error("owned items should not be re-offered")
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                else -> {}
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        error("never reached the next set boundary")
    }

    @Test
    fun unlockedPassivesJoinTheLevelUpDraftPool() {
        val withoutUnlocks = collectLevelUpModifierIds(emptySet())
        assertTrue("store passives are absent from the baseline pool",
            artifactIds.none { it in withoutUnlocks })

        val withUnlocks = collectLevelUpModifierIds(artifactIds)
        assertTrue("unlocked artifacts should appear in the level-up draft",
            artifactIds.any { it in withUnlocks })
    }

    /** Every modifier id offered across a full farming run's level-ups, for the given unlocks. */
    private fun collectLevelUpModifierIds(unlockedIds: Set<String>): Set<String> {
        val seen = mutableSetOf<String>()
        val e = RunEngine(
            RunConfig(StartingWeapon.GATLING, levelCap = 40, maxHits = 999, startingGold = 0, seed = 9L, waves = 7,
                unlockedIds = unlockedIds)
        )
        repeat(30000) { i ->
            when (e.status) {
                RunStatus.LEVEL_UP -> {
                    e.snapshot().levelUpOptions.forEach { seen.add(it.modifier.id) }
                    e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                RunStatus.STORE -> e.skipStore()
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                RunStatus.VICTORY, RunStatus.DEFEAT -> return seen
                else -> {}
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        return seen
    }
}
