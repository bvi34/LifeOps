package com.lifeops.app.game.run

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.EquipmentUpgrades
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Modifier

/**
 * The between-set store (DESIGN.md §9): rolling its offers, spending on them, and the mutators a
 * purchase applies to the rest of the run.

 * Also where a run ends, because ending it is the other thing that can happen when a set closes.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

/**
 * After the set draft, open the store on up to [RunEngine.STORE_OFFER_COUNT] random unowned offers; if the
 * catalog is exhausted (everything owned) there is nothing to show, so roll straight on.
 */
internal fun RunEngine.enterStoreOrResume() {
    storeOptions = rollStoreOffers()
    if (storeOptions.isNotEmpty()) {
        status = RunStatus.STORE
    } else {
        resumeIntoNextSet()
    }
}

/** Four random offers drawn from the catalog minus everything already owned this run. */
internal fun RunEngine.rollStoreOffers(): List<StoreOffer> {
    val pool = StoreCatalog.ITEMS.filter { it.id !in runUnlockedIds }.toMutableList()
    val out = ArrayList<StoreOffer>(RunEngine.STORE_OFFER_COUNT)
    while (pool.isNotEmpty() && out.size < RunEngine.STORE_OFFER_COUNT) {
        val item = pool.removeAt(rng.nextInt(pool.size))
        out.add(StoreOffer(item.id, item.name, item.description, item.category.label, item.category.cost))
    }
    return out
}

/**
 * Apply a bought store item to this run and resume (DESIGN.md §9). The Modifier-Budget spend and
 * the permanent unlock record are handled by the caller against the bank — the engine never
 * touches banked resources (the same contract as [revive]). No-op unless paused on the store.
 */
internal fun RunEngine.applyStorePurchase(itemId: String) {
    if (status != RunStatus.STORE) return
    StoreCatalog.byId(itemId)?.let { item ->
        when (item) {
            is StoreCatalog.Item.ArtifactItem -> grantArtifact(item.modifier)   // joins the build + pool
            is StoreCatalog.Item.GunItem -> swapWeapon(item.weapon)             // replaces the aimed weapon
            is StoreCatalog.Item.MutatorItem -> applyMutator(item)             // run-wide effect
        }
        runUnlockedIds.add(item.id)
    }
    resumeIntoNextSet()
}

/** Decline the store this set (DESIGN.md §9). No-op unless paused on the store. */
internal fun RunEngine.skipStore() {
    if (status != RunStatus.STORE) return
    resumeIntoNextSet()
}

internal fun RunEngine.resumeIntoNextSet() {
    storeOptions = emptyList()
    breatherTimer = RunEngine.BREATHER_SECONDS * 1.5f
    beginWave(0)
    status = RunStatus.RUNNING
}

/** Grant (or rank up) a store artifact immediately, so it's live for the rest of this run. */
internal fun RunEngine.grantArtifact(mod: Modifier) {
    val idx = player.held.indexOfFirst { it.modifier.id == mod.id }
    if (idx >= 0) {
        val next = (player.held[idx].rank + 1).coerceAtMost(mod.maxRank)
        player.held[idx] = HeldModifier(mod, next)
    } else {
        player.held.add(HeldModifier(mod, 1))
    }
    rebuildStats()
}

/** Swap the aimed weapon to a store gun: reset firing state and hand over a full magazine. */
internal fun RunEngine.swapWeapon(newWeapon: StartingWeapon) {
    player.weapon = newWeapon
    player.reloadRemaining = 0f
    player.spin = 0f
    player.fireCooldown = 0f
    rebuildStats()
    player.ammo = player.magazine(stats)
}

/** Fold a mutator's run-wide bonuses/banes in (DESIGN.md §9). Used at run start and on purchase. */
internal fun RunEngine.applyMutator(item: StoreCatalog.Item.MutatorItem) {
    player.runBonuses.addAll(item.playerBonuses)
    item.directorBanes.forEach { director.addRunBane(it) }
    rebuildStats()
}

/** The draft pool: the baseline artifacts plus every passive/equipment unlocked from the store
 *  (DESIGN.md §9). This is how a bought item "becomes part of the level-up pool" for the run. */
internal fun RunEngine.artifactPool(): List<Modifier> = Artifacts.ALL + StoreCatalog.unlockedArtifacts(runUnlockedIds)

internal fun RunEngine.rollLevelUpOptions(): List<LevelUpOption> {
    val candidates = artifactPool().mapNotNull { mod ->
        val held = player.held.firstOrNull { it.modifier.id == mod.id }
        val currentRank = held?.rank ?: 0
        if (currentRank >= mod.maxRank) null
        else {
            val resultingRank = currentRank + 1
            // Combat-equipment ranks past the first roll a random upgrade from *that equipment's*
            // pool (DESIGN.md §5/§9), so the offer shows exactly which improvement this pick grants.
            val pool = EquipmentUpgrades.poolFor(mod.id)
            val upgrade = if (mod.category == com.lifeops.app.game.core.ArtifactCategory.COMBAT_EQUIPMENT &&
                resultingRank >= 2 && pool.isNotEmpty())
                pool[rng.nextInt(pool.size)] else null
            LevelUpOption(mod, resultingRank, isNew = held == null, equipmentUpgrade = upgrade)
        }
    }
    if (candidates.isEmpty()) return emptyList()
    // Shuffle deterministically via the run RNG and take up to 3 distinct offers.
    val pool = candidates.toMutableList()
    val out = ArrayList<LevelUpOption>(3)
    while (pool.isNotEmpty() && out.size < 3) {
        out.add(pool.removeAt(rng.nextInt(pool.size)))
    }
    return out
}

internal fun RunEngine.endRun(victory: Boolean) {
    if (status == RunStatus.VICTORY || status == RunStatus.DEFEAT) return
    status = if (victory) RunStatus.VICTORY else RunStatus.DEFEAT
    bus.emit(GameEvent.OnRunEnd(victory, score))
}

/**
 * Buy back into the same run after a defeat (DESIGN.md §7). Restores full hearts, grants a grace
 * window of invulnerability, and clears the swarm (and enemy fire) around the player so the
 * revive lands in breathing room instead of straight back into the pile that overran them.
 * Bosses are left standing — the i-frames are the time to reposition, not a free boss wipe.
 * The energy price is charged by the caller against the bank; the engine never touches banked
 * resources. No-op unless the run is currently in defeat.
 */
internal fun RunEngine.revive() {
    if (status != RunStatus.DEFEAT) return
    revives++
    player.hits = player.maxHits
    player.invuln = RunEngine.REVIVE_INVULN
    player.hurtFlash = 0f
    enemies.removeAll { !it.type.isBoss && it.pos.distanceTo(player.pos) <= RunEngine.REVIVE_CLEAR_RADIUS }
    projectiles.removeAll { !it.friendly && it.pos.distanceTo(player.pos) <= RunEngine.REVIVE_CLEAR_RADIUS }
    status = RunStatus.RUNNING
}
