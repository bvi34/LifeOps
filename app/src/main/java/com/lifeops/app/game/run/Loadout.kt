package com.lifeops.app.game.run

import com.lifeops.app.data.model.GameResource

/**
 * Maps banked [GameResource]s onto a run loadout without ever converting one resource into another
 * (DESIGN.md invariant #1). Each loadout slot is funded only by its own resource: Energy gates
 * entry, and Level Cap / Max Health / Starting Gold are committed up-front and expended by the run
 * (§7). Resources are user-renamable, so slots resolve by role — a case-insensitive name match with
 * a slot-index fallback — and the whole thing degrades to base stats when a role's resource is
 * absent, so a fresh install with default slots is still playable.
 */
object Loadout {
    const val ENERGY_COST = 10          // run entry price (DESIGN.md §2: ~10 energy/run)
    const val UNITS_PER_LEVEL_CAP = 10  // banked units per in-run level of ceiling (§2)
    const val UNITS_PER_HEART = 25      // banked Max-Health units per extra heart

    const val BASE_LEVEL_CAP = 12

    /**
     * Committing beyond these amounts cannot raise the stat any further — it is already clamped at
     * its ceiling ([RunConfig.MAX_LEVEL_CAP] / [RunConfig.MAX_HITS]). The loadout caps commitments
     * here so banked resources are never spent for zero benefit.
     */
    val MAX_USEFUL_LEVEL_CAP = (RunConfig.MAX_LEVEL_CAP - BASE_LEVEL_CAP) * UNITS_PER_LEVEL_CAP
    val MAX_USEFUL_HEALTH = (RunConfig.MAX_HITS - RunConfig.BASE_HITS) * UNITS_PER_HEART

    /** A loadout slot bound to a resolved resource (or null if no resource fits the role). */
    data class Slot(val role: Role, val resource: GameResource?)

    enum class Role(val label: String, private val keywords: List<String>) {
        ENERGY("Energy", listOf("energy", "stamina", "family")),
        LEVEL_CAP("Level Cap", listOf("level", "cap", "wisdom", "beacon")),
        MAX_HEALTH("Max Health", listOf("health", "hp", "strength", "vigor", "swca")),
        STARTING_GOLD("Starting Gold", listOf("gold", "coin", "home", "money"));

        fun matches(name: String): Boolean {
            val n = name.lowercase()
            return keywords.any { n.contains(it) }
        }
    }

    /** Best-effort resolution of a role to a resource: name match first, then a slot-index fallback. */
    fun resolve(role: Role, resources: List<GameResource>): GameResource? {
        resources.firstOrNull { role.matches(it.name) }?.let { return it }
        val fallbackIndex = when (role) {
            Role.STARTING_GOLD -> 0
            Role.ENERGY -> 1
            Role.LEVEL_CAP -> 2
            Role.MAX_HEALTH -> 3
        }
        return resources.firstOrNull { it.slotIndex == fallbackIndex }
    }

    fun energyResource(resources: List<GameResource>): GameResource? = resolve(Role.ENERGY, resources)

    fun canAfford(resources: List<GameResource>): Boolean =
        (energyResource(resources)?.currentValue ?: 0) >= ENERGY_COST

    fun levelCapFor(committed: Int): Int =
        (BASE_LEVEL_CAP + committed / UNITS_PER_LEVEL_CAP)
            .coerceIn(RunConfig.MIN_LEVEL_CAP, RunConfig.MAX_LEVEL_CAP)

    /** Baseline 3 hearts, +1 per [UNITS_PER_HEART] banked Max-Health units. */
    fun heartsFor(committed: Int): Int =
        (RunConfig.BASE_HITS + committed / UNITS_PER_HEART).coerceIn(RunConfig.BASE_HITS, RunConfig.MAX_HITS)
}
