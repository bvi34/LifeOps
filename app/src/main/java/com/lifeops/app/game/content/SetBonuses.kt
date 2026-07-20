package com.lifeops.app.game.content

import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatContribution

/**
 * The per-set draft pools (DESIGN.md §7 escalation, authored as data per invariant #4). Every time
 * the run clears a full set of waves + the boss roster and loops into the next set, the player is
 * offered a choice that pairs a [Boon] (a run-scoped player stat gain) with a [Bane] (a run-scoped
 * enemy buff the Director hosts). Powering up always powers the enemies up too — the risk/reward
 * pulse of the endless loop.
 *
 * A boon is a player-attached [StatContribution]; a bane is a Director-attached one (an
 * `ENEMY_*_MULT` / `SPAWN_MULT` stat, which only the run's Director reads — DESIGN.md §8). Both are
 * folded through the same [StatContribution] resolver everything else uses, so adding a boon or bane
 * is authoring a row here, not writing engine code.
 */
object SetBonuses {

    /** A player stat gain, granted for the rest of the run when its option is chosen. */
    data class Boon(
        val id: String,
        val name: String,
        val description: String,
        val contribution: StatContribution,
    )

    /** An enemy buff the Director applies to every enemy spawned after this set's draft. */
    data class Bane(
        val id: String,
        val name: String,
        val description: String,
        val contribution: StatContribution,
    )

    val BOONS: List<Boon> = listOf(
        Boon("boon_damage", "Hollow Points", "+15% aimed damage",
            StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 0.15f)),
        Boon("boon_firerate", "Hair Trigger", "+15% aimed fire rate",
            StatContribution(Stat.FIRE_RATE, Scope.AIMED, Op.ADD_PERCENT, 0.15f)),
        Boon("boon_move", "Fleet Footed", "+18% move speed",
            StatContribution(Stat.MOVE_SPEED, Scope.GLOBAL, Op.ADD_PERCENT, 0.18f)),
        Boon("boon_projectile", "Fork", "+1 aimed projectile",
            StatContribution(Stat.PROJECTILES, Scope.AIMED, Op.FLAT, 1f)),
        Boon("boon_crit", "Killer Instinct", "+12% crit chance",
            StatContribution(Stat.CRIT_CHANCE, Scope.AIMED, Op.FLAT, 0.12f)),
        Boon("boon_pickup", "Magnetize", "+30% pickup radius",
            StatContribution(Stat.PICKUP_RADIUS, Scope.GLOBAL, Op.ADD_PERCENT, 0.30f)),
        Boon("boon_xp", "Fast Learner", "+25% XP gain",
            StatContribution(Stat.XP_GAIN, Scope.GLOBAL, Op.ADD_PERCENT, 0.25f)),
        Boon("boon_range", "Long Barrel", "+15% range",
            StatContribution(Stat.RANGE, Scope.AIMED, Op.ADD_PERCENT, 0.15f)),
    )

    // Only Director stats the engine actually reads at spawn are used, so every bane genuinely bites
    // (ENEMY_DAMAGE_MULT is not applied to the discrete-heart contact model, so it is left out).
    val BANES: List<Bane> = listOf(
        Bane("bane_hp", "Thick Hide", "Enemies gain +18% health",
            StatContribution(Stat.ENEMY_HP_MULT, Scope.GLOBAL, Op.ADD_PERCENT, 0.18f)),
        Bane("bane_speed", "Frenzy", "Enemies move +12% faster",
            StatContribution(Stat.ENEMY_SPEED_MULT, Scope.GLOBAL, Op.ADD_PERCENT, 0.12f)),
        Bane("bane_spawn", "Horde", "+25% more enemies per wave",
            StatContribution(Stat.SPAWN_MULT, Scope.GLOBAL, Op.ADD_PERCENT, 0.25f)),
    )
}

/**
 * The instant rewards offered when the XP bar overflows past the level cap (DESIGN.md §6). Each
 * overflow fill is a micro-pick of these — bonus gold, healing, or a short temporary stat boost.
 * All three are strictly in-run: overflow gold dies with the run (protects non-fungibility,
 * invariant #1), and the temp boost expires after [TempBoosts.DURATION_SECONDS].
 */
object TempBoosts {
    const val DURATION_SECONDS = 8f

    /** One temporary stat surge the overflow pick can grant. */
    data class Surge(
        val id: String,
        val label: String,
        val contribution: StatContribution,
    )

    val SURGES: List<Surge> = listOf(
        Surge("surge_damage", "Overdrive · +50% damage",
            StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 0.50f)),
        Surge("surge_firerate", "Frenzy · +40% fire rate",
            StatContribution(Stat.FIRE_RATE, Scope.AIMED, Op.ADD_PERCENT, 0.40f)),
        Surge("surge_move", "Sprint · +35% move speed",
            StatContribution(Stat.MOVE_SPEED, Scope.GLOBAL, Op.ADD_PERCENT, 0.35f)),
    )
}
