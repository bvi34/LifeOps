package com.lifeops.app.game.content

import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatContribution

/**
 * The variable-upgrade pools each combat-equipment artifact rolls from on every rank past the first
 * (DESIGN.md §5/§9). Rank 1 deploys the equipment; ranks 2+ each roll a *random* improvement from
 * that equipment's pool, so the same equipment grows a different way each run.
 *
 * One registry, many equipments: each pool is a list of authored [Upgrade] rows (a stat
 * contribution), and [poolFor] maps an equipment's modifier id to its pool. Adding an upgrade is a
 * row here; adding a whole new equipment is a new pool + a case in [poolFor] — never engine code in
 * the draft path. The engine applies the chosen [Upgrade.contribution] into the player's build, and
 * whichever equipment reads that stat picks it up.
 */
object EquipmentUpgrades {
    data class Upgrade(val id: String, val label: String, val contribution: StatContribution)

    /** Turret pool — AUTO-scope weapon stats, so upgrades lift the deployed turrets, not the aimed gun. */
    val TURRET: List<Upgrade> = listOf(
        Upgrade("t_damage", "+30% turret damage",
            StatContribution(Stat.DAMAGE, Scope.AUTO, Op.ADD_PERCENT, 0.30f)),
        Upgrade("t_firerate", "+30% turret fire rate",
            StatContribution(Stat.FIRE_RATE, Scope.AUTO, Op.ADD_PERCENT, 0.30f)),
        Upgrade("t_projectile", "+1 turret projectile",
            StatContribution(Stat.PROJECTILES, Scope.AUTO, Op.FLAT, 1f)),
        Upgrade("t_range", "+25% turret range",
            StatContribution(Stat.RANGE, Scope.AUTO, Op.ADD_PERCENT, 0.25f)),
        Upgrade("t_count", "+1 extra turret",
            StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 1f)),
    )

    /** Mine pool — dedicated MINE_* stats, so mine upgrades never bleed onto turrets or the aimed gun. */
    val MINES: List<Upgrade> = listOf(
        Upgrade("m_count", "+1 mine",
            StatContribution(Stat.MINE_COUNT, Scope.GLOBAL, Op.FLAT, 1f)),
        Upgrade("m_damage", "+40% mine damage",
            StatContribution(Stat.MINE_DAMAGE, Scope.GLOBAL, Op.ADD_PERCENT, 0.40f)),
        Upgrade("m_radius", "+30% mine blast radius",
            StatContribution(Stat.MINE_RADIUS, Scope.GLOBAL, Op.ADD_PERCENT, 0.30f)),
        Upgrade("m_trigger", "+40% mine trigger range",
            StatContribution(Stat.MINE_TRIGGER, Scope.GLOBAL, Op.ADD_PERCENT, 0.40f)),
    )

    /** Decoy pool — dedicated DECOY_* stats. Grows the lure's numbers and can arm a death blast. */
    val DECOY: List<Upgrade> = listOf(
        Upgrade("d_count", "+1 decoy",
            StatContribution(Stat.DECOY_COUNT, Scope.GLOBAL, Op.FLAT, 1f)),
        Upgrade("d_hp", "+60% decoy durability",
            StatContribution(Stat.DECOY_HP, Scope.GLOBAL, Op.ADD_PERCENT, 0.60f)),
        Upgrade("d_range", "+35% decoy lure range",
            StatContribution(Stat.DECOY_RANGE, Scope.GLOBAL, Op.ADD_PERCENT, 0.35f)),
        Upgrade("d_blast", "decoys detonate when destroyed",
            StatContribution(Stat.DECOY_BLAST_RADIUS, Scope.GLOBAL, Op.FLAT, 55f)),
    )

    /** The pool an equipment artifact rolls from, by its modifier id. Empty for non-equipment. */
    fun poolFor(equipmentId: String): List<Upgrade> = when (equipmentId) {
        "turret", "sentry_array" -> TURRET
        "mines" -> MINES
        "decoy" -> DECOY
        else -> emptyList()
    }
}
