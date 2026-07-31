package com.lifeops.app.game.core

/**
 * What a modifier attaches to (DESIGN.md invariant #4 + §9). One modifier system, three faces —
 * artifacts attach to an entity, Phase-2 shop items attach to the player permanently, challenge
 * modes attach to the run/director. They differ only by this tag, so adding content is authoring
 * a row, not writing engine code.
 */
enum class AttachTarget { ENTITY, PLAYER, RUN_DIRECTOR }

/**
 * The two authored faces of an entity artifact (the level-up draft pool splits along this):
 *
 * - [COMBAT_EQUIPMENT] deploys an automatic weapon — the VS build layer (DESIGN.md §4). The turret
 *   is the baseline example: its rank rows add [Stat.TURRET_COUNT], and the engine reads that stat
 *   to keep that many auto-turrets deployed. Still "stat math only" (§5) — the behaviour is the
 *   engine reading a stat, not code baked into the artifact.
 * - [STAT_SUPPORT] does pure stat math on an existing weapon/entity (Overclock, Splitter, …).
 *
 * Challenge modes and other non-artifact modifiers leave this at its [STAT_SUPPORT] default; it is
 * only consulted when a modifier is offered as a level-up artifact.
 */
enum class ArtifactCategory { COMBAT_EQUIPMENT, STAT_SUPPORT }

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
    /** Which draft face this artifact presents (DESIGN.md §5). Inert on non-artifact modifiers. */
    val category: ArtifactCategory = ArtifactCategory.STAT_SUPPORT,
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
