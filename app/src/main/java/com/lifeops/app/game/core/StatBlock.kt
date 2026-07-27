package com.lifeops.app.game.core

/**
 * Scope tag carried by every stat row (DESIGN.md §3). Cross-entity meaning is assigned only
 * through explicit remap tables, never by field-name coincidence — an unmapped stat is inert
 * on an entity that doesn't read it.
 *
 * - [AIMED]  boosts the player's aimed starting weapon (the SAS skill layer).
 * - [AUTO]   boosts artifact weapons (turrets etc. — the VS build layer).
 * - [GLOBAL] entity-wide stats: move speed, max health, XP gain, pickup radius.
 */
enum class Scope { AIMED, AUTO, GLOBAL }

/**
 * The stat keys the baseline game reads. Player, enemies, turrets and artifacts all share this
 * one schema (DESIGN.md §3), so an artifact that reads [DAMAGE] works identically whoever wields
 * it. New content adds rows, not fields.
 */
enum class Stat {
    DAMAGE,          // per-hit damage
    FIRE_RATE,       // shots per second (for the Gatling this is the spin-up ceiling — DESIGN.md §4)
    RELOAD_SPEED,    // reload-speed multiplier (base 1.0; effective reload time = base / this)
    MAGAZINE,        // rounds per magazine (Extended Mag raises it — DESIGN.md §4)
    PROJECTILES,     // projectiles per shot (secretly multiplicative — DESIGN.md §5)
    PROJECTILE_SPEED,
    RANGE,
    CRIT_CHANCE,     // 0..1
    CRIT_MULT,       // e.g. 2.0
    MOVE_SPEED,
    MAX_HEALTH,
    PICKUP_RADIUS,
    XP_GAIN,         // multiplier on collected XP
    TURRET_COUNT,    // concurrent artifact turrets (also secretly multiplicative)
    TURRET_TTL,      // seconds a turret lives

    // On-hit projectile behaviours (DESIGN.md §9 store passives). Read at fire time and stamped onto
    // each shot, so they transform *any* weapon's projectiles — the engine reads the stat, no
    // per-weapon code (the same "stat, not code" rule as TURRET_COUNT).
    PIERCE,          // extra enemies a shot passes through before it's spent
    RICOCHET,        // times a spent shot bounces to a new nearby target
    EXPLOSION_RADIUS,// world-unit blast radius on impact (0 = no explosion)

    // Director-scoped stats (DESIGN.md §8). Inert on the player/enemies — only the run's Director
    // reads them. Remap tables feed player stats into these; challenge-mode modifiers set them
    // directly. Adding a challenge variant is authoring these values, not writing engine code.
    SPAWN_MULT,        // multiplies how many enemies a wave spawns
    ENEMY_HP_MULT,     // multiplies each spawned enemy's max health
    ENEMY_SPEED_MULT,  // multiplies each spawned enemy's move speed
    ENEMY_DAMAGE_MULT, // multiplies each spawned enemy's touch damage
}

/**
 * How a modifier's value folds into the running total for a stat. Baseline artifacts are
 * pure-additive (DESIGN.md §5); [MULTIPLY] and [FLAT] exist for the Phase-2 shop and enemy
 * scaling, so the resolver already understands them.
 */
enum class Op { ADD_PERCENT, MULTIPLY, FLAT }

/** One contribution to one (stat, scope) bucket. Immutable; produced from modifier data rows. */
data class StatContribution(val stat: Stat, val scope: Scope, val op: Op, val value: Float)

/**
 * Resolves a base value + a list of contributions for a given (stat, scope) into a final number.
 *
 * Order is deterministic and legible so authored content behaves predictably: start at base,
 * sum ADD_PERCENT into one percentage, add FLAT, then apply MULTIPLY factors. That keeps
 * "+10% damage ×4 ranks" reading as +40% (DESIGN.md §5) rather than compounding.
 */
class StatBlock(private val base: Map<Stat, Float> = emptyMap()) {

    private val contributions = ArrayList<StatContribution>()

    fun baseOf(stat: Stat): Float = base[stat] ?: DEFAULT_BASE[stat] ?: 0f

    fun add(contribution: StatContribution): StatBlock {
        contributions.add(contribution)
        return this
    }

    fun addAll(list: List<StatContribution>): StatBlock {
        contributions.addAll(list)
        return this
    }

    fun clearContributions() = contributions.clear()

    /**
     * Final value for [stat] at [scope]. GLOBAL contributions always apply; a scoped stat also
     * receives contributions tagged with that same scope. AIMED and AUTO never bleed into each
     * other — that separation is the whole point of the scope tag.
     */
    fun resolve(stat: Stat, scope: Scope = Scope.GLOBAL): Float {
        var percent = 0f
        var flat = 0f
        var mult = 1f
        for (c in contributions) {
            if (c.stat != stat) continue
            if (c.scope != Scope.GLOBAL && c.scope != scope) continue
            when (c.op) {
                Op.ADD_PERCENT -> percent += c.value
                Op.FLAT -> flat += c.value
                Op.MULTIPLY -> mult *= c.value
            }
        }
        return (baseOf(stat) * (1f + percent) + flat) * mult
    }

    companion object {
        /** Sensible zero-ish defaults so an unset stat resolves rather than throwing. */
        val DEFAULT_BASE: Map<Stat, Float> = mapOf(
            Stat.DAMAGE to 0f,
            Stat.FIRE_RATE to 1f,
            Stat.RELOAD_SPEED to 1f,
            Stat.MAGAZINE to 10f,
            Stat.PROJECTILES to 1f,
            Stat.PROJECTILE_SPEED to 320f,
            Stat.RANGE to 480f,
            Stat.CRIT_CHANCE to 0f,
            Stat.CRIT_MULT to 2f,
            Stat.MOVE_SPEED to 130f,
            Stat.MAX_HEALTH to 100f,
            Stat.PICKUP_RADIUS to 60f,
            Stat.XP_GAIN to 1f,
            Stat.TURRET_COUNT to 0f,
            Stat.TURRET_TTL to 8f,
            Stat.PIERCE to 0f,
            Stat.RICOCHET to 0f,
            Stat.EXPLOSION_RADIUS to 0f,
            Stat.SPAWN_MULT to 1f,
            Stat.ENEMY_HP_MULT to 1f,
            Stat.ENEMY_SPEED_MULT to 1f,
            Stat.ENEMY_DAMAGE_MULT to 1f,
        )
    }
}
