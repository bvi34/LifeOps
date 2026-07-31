package com.lifeops.app.game.content

import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Remap
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat

/**
 * A challenge mode is one modifier row with `attaches_to: run/director` (DESIGN.md §8) — expressed
 * here as the bundle the [com.lifeops.app.game.run.Director] hosts. Three data fields, no per-mode
 * engine code: [directorModifiers] set Director stats outright, [remaps] wire player stats into
 * Director stats (Mirror), and [enemyModifiers] ride along on every spawned enemy (Mob Boss).
 * Adding a new variant is adding a row here.
 */
data class ChallengeMode(
    val id: String,
    val name: String,
    val description: String,
    val directorModifiers: List<HeldModifier> = emptyList(),
    val remaps: List<Remap> = emptyList(),
    val enemyModifiers: List<HeldModifier> = emptyList(),
) {
    companion object {
        val NONE = ChallengeMode(
            id = "none",
            name = "Standard",
            description = "No modifiers. The baseline run.",
        )

        /**
         * Mirror: the enemy Director receives the player's boosts through remap rows, so difficulty
         * inflates in lockstep with player power. More projectiles → denser waves; more aimed damage
         * → tougher enemies. Self-balancing — different remap rows make different Mirror variants.
         */
        val MIRROR = ChallengeMode(
            id = "mirror",
            name = "Mirror",
            description = "Waves scale with your firepower. Every upgrade upgrades them too.",
            remaps = listOf(
                Remap(Stat.PROJECTILES, Scope.AIMED, Stat.SPAWN_MULT, factor = 0.6f),
                Remap(Stat.DAMAGE, Scope.AIMED, Stat.ENEMY_HP_MULT, factor = 0.8f),
                Remap(Stat.FIRE_RATE, Scope.AIMED, Stat.ENEMY_SPEED_MULT, factor = 0.4f),
            ),
        )

        /**
         * Mob Boss: every enemy spawns holding an artifact, through the identical attachment code
         * path the player uses. Adrenaline is GLOBAL move speed, so it bites even on enemies that
         * have no weapon; aimed-scope artifacts remain inert until enemies gain weapons — exactly
         * the "unmapped stats are inert" rule (DESIGN.md §3), demonstrated, not special-cased.
         */
        val MOB_BOSS = ChallengeMode(
            id = "mob_boss",
            name = "Mob Boss",
            description = "Every enemy wields an artifact. They move like you do.",
            enemyModifiers = listOf(HeldModifier(Artifacts.ADRENALINE, rank = 3)),
        )

        val ALL: List<ChallengeMode> = listOf(NONE, MIRROR, MOB_BOSS)

        fun byId(id: String): ChallengeMode = ALL.firstOrNull { it.id == id } ?: NONE
    }
}
