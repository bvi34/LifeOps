package com.lifeops.app.game.run

import com.lifeops.app.game.content.ChallengeMode
import com.lifeops.app.game.core.EventBus
import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution

/**
 * The run's enemy Director — the host of every enemy-modifying decision (DESIGN.md §8). It holds
 * the [ChallengeMode]'s `RUN_DIRECTOR`-attached modifiers and remap tables, and exposes the
 * per-enemy multipliers the engine applies at spawn. It is one face of the single modifier system:
 * artifacts attach to entities, shop items to the player, and challenge modes attach *here*.
 *
 * Two mechanisms, both pure data:
 *  - **Mirror** — [Remap] rows read the player's build and drive `SPAWN_MULT` / `ENEMY_HP_MULT` etc.
 *  - **Mob Boss** — [enemyModifiers] are attached to every spawned enemy through the same StatBlock
 *    code path the player uses (an artifact wielded by an enemy is the identical mechanism).
 */
class Director(private val bus: EventBus) {

    var mode: ChallengeMode = ChallengeMode.NONE
        private set

    /** Player stat values captured at run start; Mirror measures growth relative to these. */
    private val baseline = HashMap<Pair<Stat, Scope>, Float>()
    private var stats: StatBlock = StatBlock(DIRECTOR_BASE)

    /** Modifiers attached to each enemy as it spawns (Mob Boss). Same shape as player-held artifacts. */
    val enemyModifiers: List<HeldModifier> get() = mode.enemyModifiers

    /** Install a challenge mode and snapshot the player's baseline for any remap sources. */
    fun configure(mode: ChallengeMode, playerStats: StatBlock) {
        this.mode = mode
        baseline.clear()
        mode.remaps.forEach { r ->
            baseline[r.fromStat to r.fromScope] = playerStats.resolve(r.fromStat, r.fromScope)
        }
        rebuild(playerStats)
    }

    /**
     * Recompute the Director's stats from its modifiers + remaps against the current player build.
     * Call whenever the player's build changes (a level-up pick) so Mirror tracks it. Existing live
     * enemies keep their spawn-time stats; newly spawned ones pick up the new multipliers — the
     * lockstep inflation happens at the spawn boundary, never by retroactively mutating the field.
     */
    fun rebuild(playerStats: StatBlock) {
        val b = StatBlock(DIRECTOR_BASE)
        mode.directorModifiers.forEach { b.addAll(it.contributions()) }
        mode.remaps.forEach { r ->
            val base = baseline[r.fromStat to r.fromScope] ?: return@forEach
            if (base <= EPS) return@forEach
            val now = playerStats.resolve(r.fromStat, r.fromScope)
            val growth = (now / base - 1f).coerceAtLeast(0f)
            if (growth > 0f) {
                b.add(StatContribution(r.toStat, Scope.GLOBAL, Op.ADD_PERCENT, growth * r.factor))
            }
        }
        stats = b
    }

    fun spawnMult(): Float = stats.resolve(Stat.SPAWN_MULT).coerceAtLeast(0.1f)
    fun enemyHpMult(): Float = stats.resolve(Stat.ENEMY_HP_MULT).coerceAtLeast(0.1f)
    fun enemySpeedMult(): Float = stats.resolve(Stat.ENEMY_SPEED_MULT).coerceAtLeast(0.1f)
    fun enemyDamageMult(): Float = stats.resolve(Stat.ENEMY_DAMAGE_MULT).coerceAtLeast(0.1f)

    companion object {
        private const val EPS = 1e-4f
        val DIRECTOR_BASE: Map<Stat, Float> = mapOf(
            Stat.SPAWN_MULT to 1f,
            Stat.ENEMY_HP_MULT to 1f,
            Stat.ENEMY_SPEED_MULT to 1f,
            Stat.ENEMY_DAMAGE_MULT to 1f,
        )
    }
}
