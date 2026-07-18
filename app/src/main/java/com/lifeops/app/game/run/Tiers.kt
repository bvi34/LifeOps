package com.lifeops.app.game.run

import kotlin.math.min

/**
 * Endless-mode difficulty scaling (DESIGN.md §7). Clearing all waves + the boss loops the run back
 * to wave 1 at the next tier — the enemies come back "leveled up". These are pure multipliers keyed
 * on the tier, applied to each spawned enemy and to wave size, so escalation is data-shaped and
 * easy to re-tune. Speed is capped so late tiers stay readable rather than becoming undodgeable.
 */
object Tiers {
    fun hpMult(tier: Int): Float = 1f + tier * 0.35f
    fun speedMult(tier: Int): Float = 1f + min(tier * 0.05f, 0.5f)
    fun damageMult(tier: Int): Float = 1f + tier * 0.15f
    fun spawnMult(tier: Int): Float = 1f + tier * 0.20f
}
