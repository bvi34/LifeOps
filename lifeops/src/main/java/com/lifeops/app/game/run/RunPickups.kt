package com.lifeops.app.game.run

import com.lifeops.app.game.content.SetBonuses
import com.lifeops.app.game.content.TempBoosts
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.PickupKind

/**
 * What a kill leaves on the floor, and what picking it up is worth: experience, the level-up that
 * follows, and the set bonuses an overflow is spent on.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

internal fun RunEngine.updatePickups(dt: Float) {
    // A set-bonus draft can be entered earlier this same frame (updateWaves). Don't collect XP
    // into a level-up/overflow that would clobber that pause and skip beginning the next set —
    // the pickups wait on the field until the run resumes.
    if (status != RunStatus.RUNNING) return
    val radius = player.pickupRadius(stats)
    val it = pickups.iterator()
    while (it.hasNext()) {
        val pk = it.next()
        val dist = pk.pos.distanceTo(player.pos)
        if (dist <= radius) {
            // Magnetize toward the player, then collect on contact.
            val pull = (player.pos - pk.pos).normalized() * (260f * dt)
            pk.pos = pk.pos + pull
        }
        if (pk.pos.distanceTo(player.pos) <= player.radius + pk.radius) {
            collect(pk)
            it.remove()
        }
    }
}

internal fun RunEngine.collect(pk: Pickup) {
    when (pk.kind) {
        PickupKind.GOLD -> player.gold += pk.amount
        PickupKind.HEALTH -> {
            player.hits = (player.hits + pk.amount).coerceAtMost(player.maxHits)
            bus.emit(GameEvent.OnHeal(RunEngine.PLAYER_ID, pk.amount.toFloat()))
        }
        PickupKind.XP -> gainXp(pk.amount.toFloat())
    }
    bus.emit(GameEvent.OnPickup(RunEngine.PLAYER_ID, pk.kind))
}

internal fun RunEngine.gainXp(amount: Float) {
    val gain = amount * stats.resolve(com.lifeops.app.game.core.Stat.XP_GAIN)
    player.xp += gain
    // Fill may cross the threshold multiple times in one collection; loop, but cap iterations
    // so a pathological XP_GAIN can't spin forever (degrade, don't hang — invariant #3).
    var guard = 0
    while (player.xp >= player.xpToNext && guard < 32) {
        guard++
        player.xp -= player.xpToNext
        if (player.level < config.levelCap) {
            levelUp()
            // A real level-up pauses for a pick; a fully-maxed build falls through to an
            // overflow pick instead. Either pause stops draining XP until the player resumes.
            if (status == RunStatus.LEVEL_UP || status == RunStatus.OVERFLOW) break
        } else {
            overflowLevel()
            // The overflow micro-pick also pauses; resume drains any leftover XP next tick.
            if (status == RunStatus.OVERFLOW) break
        }
    }
}

internal fun RunEngine.levelUp() {
    player.level++
    player.xpToNext *= 1.28f
    bus.emit(GameEvent.OnLevelUp(player.level, overflow = false))
    val options = rollLevelUpOptions()
    if (options.isEmpty()) {
        // Everything maxed: no dead pause — treat as an overflow reward instead.
        overflowLevel()
    } else {
        levelUpOptions = options
        status = RunStatus.LEVEL_UP
    }
}

/**
 * At cap the XP bar keeps filling; each fill offers an instant micro-pick (DESIGN.md §6) — bonus
 * gold, healing, or a short temporary stat boost. The run pauses on the pick; [chooseOverflow]
 * applies it and resumes.
 */
internal fun RunEngine.overflowLevel() {
    player.xpToNext *= 1.15f
    bus.emit(GameEvent.OnLevelUp(player.level, overflow = true))
    score += 50L
    overflowOptions = rollOverflowOptions()
    status = RunStatus.OVERFLOW
}

/** Roll the three overflow offers: bonus gold, a heal, and one random temporary surge. */
internal fun RunEngine.rollOverflowOptions(): List<OverflowOption> {
    val surge = TempBoosts.SURGES[rng.nextInt(TempBoosts.SURGES.size)]
    return listOf(
        OverflowOption(OverflowKind.GOLD, "Bonus Gold", "+8 gold for this run", amount = 8),
        OverflowOption(OverflowKind.HEAL, "Field Medic", "Restore 1 heart", amount = 1),
        OverflowOption(
            OverflowKind.TEMP_BOOST, surge.label,
            "Lasts ${TempBoosts.DURATION_SECONDS.toInt()}s", surge = surge,
        ),
    )
}

/** Apply the chosen overflow micro-pick and resume (DESIGN.md §6). No-op unless paused on it. */
internal fun RunEngine.chooseOverflow(option: OverflowOption) {
    if (status != RunStatus.OVERFLOW) return
    when (option.kind) {
        OverflowKind.GOLD -> player.gold += option.amount // dies with the run (invariant #1)
        OverflowKind.HEAL -> {
            val before = player.hits
            player.hits = (player.hits + option.amount).coerceAtMost(player.maxHits)
            if (player.hits > before) bus.emit(GameEvent.OnHeal(RunEngine.PLAYER_ID, (player.hits - before).toFloat()))
        }
        OverflowKind.TEMP_BOOST -> option.surge?.let {
            player.tempBuffs.add(TempBuff(it.contribution, it.label, TempBoosts.DURATION_SECONDS))
            rebuildStats()
        }
    }
    score += 40L
    overflowOptions = emptyList()
    status = RunStatus.RUNNING
}

/** Roll the per-set draft: three distinct boons, each paired with a random enemy bane (§7). */
internal fun RunEngine.rollSetBonusOptions(): List<SetBonusOption> {
    val boonPool = SetBonuses.BOONS.toMutableList()
    val out = ArrayList<SetBonusOption>(3)
    while (boonPool.isNotEmpty() && out.size < 3) {
        val boon = boonPool.removeAt(rng.nextInt(boonPool.size))
        val bane = SetBonuses.BANES[rng.nextInt(SetBonuses.BANES.size)]
        out.add(SetBonusOption(boon, bane))
    }
    return out
}

/**
 * Apply the chosen set draft and begin the next set (DESIGN.md §7). The boon is a permanent
 * player run-bonus; the bane is layered onto the Director so every enemy spawned in the new set
 * is tougher. No-op unless paused on the set draft.
 */
internal fun RunEngine.chooseSetBonus(option: SetBonusOption) {
    if (status != RunStatus.SET_BONUS) return
    player.runBonuses.add(option.boon.contribution)
    director.addRunBane(option.bane.contribution)
    rebuildStats()
    setBonusOptions = emptyList()
    // The boon/bane draft is followed by the between-set store (DESIGN.md §9), then the next set.
    enterStoreOrResume()
}
