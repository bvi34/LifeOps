package com.lifeops.app.game.core

/**
 * What a modifier attaches to (DESIGN.md invariant #4 + §9). One modifier system, three faces —
 * artifacts attach to an entity, Phase-2 shop items attach to the player permanently, challenge
 * modes attach to the run/director. They differ only by this tag, so adding content is authoring
 * a row, not writing engine code.
 */
enum class AttachTarget { ENTITY, PLAYER, RUN_DIRECTOR }

/**
 * One authored modifier row. A baseline artifact is a set of these keyed by (id, rank): each rank
 * carries its own additive [contribution]. Rolling a duplicate raises the rank rather than
 * offering a dead pick (DESIGN.md §5). This is the seed table of the whole modifier system —
 * Phase-2 shop items and challenge modes are more rows in the same shape.
 */
data class Modifier(
    val id: String,
    val name: String,
    val description: String,
    val attachesTo: AttachTarget,
    val maxRank: Int,
    /** Additive stat contribution granted at each 1-based rank, index 0 == rank 1. */
    val rankContributions: List<StatContribution>,
) {
    init {
        require(rankContributions.size == maxRank) {
            "Modifier $id declares maxRank=$maxRank but has ${rankContributions.size} rank rows"
        }
    }

    /** Contributions active when this modifier is held at [rank] (1..maxRank): ranks 1..rank stack. */
    fun contributionsAt(rank: Int): List<StatContribution> {
        val capped = rank.coerceIn(0, maxRank)
        return rankContributions.subList(0, capped)
    }

    fun descriptionAt(rank: Int): String {
        val r = rank.coerceIn(0, maxRank)
        return "$name  ·  rank $r/$maxRank"
    }
}

/** A modifier a run currently holds, at a given rank. Held modifiers are the loadout's build. */
data class HeldModifier(val modifier: Modifier, val rank: Int) {
    fun contributions(): List<StatContribution> = modifier.contributionsAt(rank)
}

/**
 * One row of a remap table (DESIGN.md §3/§8). Cross-entity meaning is assigned ONLY here — never by
 * field-name coincidence. A remap reads one entity's stat and feeds it into another's, e.g.
 * `player.PROJECTILES → director.SPAWN_MULT`. The consumer decides how [factor] applies (the
 * Director scales the player's relative growth from a run-start baseline, so a Mirror run inflates
 * in lockstep with player power and never needs re-tuning). Remaps are data rows, not code.
 */
data class Remap(
    val fromStat: Stat,
    val fromScope: Scope,
    val toStat: Stat,
    val factor: Float,
)
