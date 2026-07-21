package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution
import com.lifeops.app.game.core.Vec2

/**
 * Live run entities. These are mutable and stepped in place — at baseline entity counts a
 * Compose Canvas handles them fine (DESIGN.md §11), and per-frame allocation churn is the thing
 * to avoid on Android. Rendering reads an immutable snapshot ([RunSnapshot]).
 */

/**
 * A temporary player buff granted by an overflow micro-pick (DESIGN.md §6). Its [contribution] folds
 * into the stat block like any other, but only while [remaining] > 0 — the engine rebuilds stats
 * when it expires so the surge cleanly falls off.
 */
class TempBuff(val contribution: StatContribution, val label: String, var remaining: Float)

class Player(
    var pos: Vec2,
    val weapon: StartingWeapon,
    val held: MutableList<HeldModifier> = mutableListOf(),
    /** Permanent (for the run) player boons drafted at each set boundary (DESIGN.md §7). */
    val runBonuses: MutableList<StatContribution> = mutableListOf(),
    /** Rolled AUTO-scope turret upgrades from Turret ranks 2-4 (DESIGN.md §5). Boost turrets only. */
    val equipmentUpgrades: MutableList<StatContribution> = mutableListOf(),
    /** Active temporary surges from overflow picks; expire and are pruned by the engine. */
    val tempBuffs: MutableList<TempBuff> = mutableListOf(),
    /** Discrete hearts: the player survives [maxHits] contacts, losing one per hit (boss: three). */
    var hits: Int,
    var maxHits: Int,
    /** Seconds of invulnerability remaining after a hit, so one contact costs one heart, not many. */
    var invuln: Float = 0f,
    var level: Int = 1,
    var xp: Float = 0f,
    var xpToNext: Float = 12f,
    var gold: Int = 0,
    var fireCooldown: Float = 0f,
    /** Rounds left in the magazine. Refilled to the resolved magazine when a reload completes. */
    var ammo: Int = 0,
    /** Seconds left on an active reload; 0 = not reloading. Firing is held while > 0 (DESIGN.md §4). */
    var reloadRemaining: Float = 0f,
    /** Seconds of continuous engaged fire, for the Gatling's spin-up. Resets when fire stops (§4). */
    var spin: Float = 0f,
    /** Current aim direction (unit), tracked every frame for the barrel/reticle. Visual + firing. */
    var aim: Vec2 = Vec2(1f, 0f),
    /** Seconds of remaining muzzle flash; set on each shot, decays each frame (visual only). */
    var muzzleFlash: Float = 0f,
    /** Seconds of remaining hurt flash; set when taking touch damage, decays each frame (visual). */
    var hurtFlash: Float = 0f,
) {
    /**
     * Rebuild the stat block from the weapon base + every held modifier's contributions. Called
     * whenever the build changes (level-up pick), not per frame. GLOBAL and the weapon's own scope
     * are what the aimed shot reads.
     */
    fun buildStats(): StatBlock {
        val block = weapon.baseStatBlock()
        held.forEach { block.addAll(it.contributions()) }
        // Drafted set boons (permanent for the run) and any active temp surges fold in the same way.
        runBonuses.forEach { block.add(it) }
        tempBuffs.forEach { block.add(it.contribution) }
        // Rolled turret upgrades are AUTO-scope, so they're inert on the aimed weapon here but let
        // the engine read the resulting TURRET_COUNT (extra-turret rolls) off the same block.
        equipmentUpgrades.forEach { block.add(it) }
        return block
    }

    /** The weapon's base magazine capacity (before the Extended Mag artifact scales it). */
    val magazineSize: Int get() = weapon.magazineSize

    /** Resolved magazine capacity: the weapon base lifted by any Extended Mag ranks (DESIGN.md §4). */
    fun magazine(stats: StatBlock): Int = stats.resolve(Stat.MAGAZINE, Scope.AIMED).toInt().coerceAtLeast(1)

    fun aimedDamage(stats: StatBlock): Float = stats.resolve(Stat.DAMAGE, Scope.AIMED)
    /** The fire-rate ceiling; for the Gatling the live cadence spins up toward this (DESIGN.md §4). */
    fun fireRate(stats: StatBlock): Float = stats.resolve(Stat.FIRE_RATE, Scope.AIMED).coerceAtLeast(0.1f)
    /** Reload-speed multiplier (Autoloader raises it); effective reload = base seconds / this. */
    fun reloadSpeed(stats: StatBlock): Float = stats.resolve(Stat.RELOAD_SPEED, Scope.AIMED).coerceAtLeast(0.1f)
    fun projectiles(stats: StatBlock): Int = stats.resolve(Stat.PROJECTILES, Scope.AIMED).toInt().coerceAtLeast(1)
    fun moveSpeed(stats: StatBlock): Float = stats.resolve(Stat.MOVE_SPEED, Scope.GLOBAL)
    fun pickupRadius(stats: StatBlock): Float = stats.resolve(Stat.PICKUP_RADIUS, Scope.GLOBAL)
    fun range(stats: StatBlock): Float = stats.resolve(Stat.RANGE, Scope.AIMED)
    fun projectileSpeed(stats: StatBlock): Float = stats.resolve(Stat.PROJECTILE_SPEED, Scope.AIMED)
    fun critChance(stats: StatBlock): Float = stats.resolve(Stat.CRIT_CHANCE, Scope.AIMED)
    fun critMult(stats: StatBlock): Float = stats.resolve(Stat.CRIT_MULT, Scope.AIMED)

    val radius: Float = 14f
}

class Enemy(
    val id: Int,
    val type: EnemyType,
    var pos: Vec2,
    var health: Float,
    val maxHealth: Float,
    /** Effective move speed after Director multipliers + any enemy-attached modifiers (Mob Boss). */
    val moveSpeed: Float,
    /** Artifacts this enemy is wielding (Mob Boss); empty in a standard run. */
    val held: List<HeldModifier> = emptyList(),
    val kind: EntityKind = if (type.isBoss) EntityKind.BOSS else EntityKind.ENEMY,
    /** Seconds of remaining hit-flash; set on each incoming hit, decays each frame (visual only). */
    var hitFlash: Float = 0f,
    /** Ranged enemies (Spitter): seconds until the next shot. */
    var fireCooldown: Float = 0f,
    /** Ranged enemies: shots fired so far in the current burst. */
    var burstShots: Int = 0,
    /** Seconds until this enemy can strike a structure again (discrete hits, not continuous drain). */
    var attackCooldown: Float = 0f,
) {
    val alive: Boolean get() = health > 0f
}

/** Kind of transient visual effect. Visual-only; never affects the simulation. */
enum class EffectKind { DEATH_BURST }

/**
 * A short-lived visual effect (e.g. an enemy death burst). The sim spawns and ages these so the
 * renderer can draw feedback without having to diff entity lists between frames; they carry no
 * gameplay weight and are purely presentational.
 */
class RunEffect(
    var pos: Vec2,
    val kind: EffectKind,
    /** Reference size in world units (e.g. the dead enemy's radius) for scaling the effect. */
    val worldRadius: Float,
    var age: Float = 0f,
    val ttl: Float,
)

class Projectile(
    val id: Int,
    val ownerId: Int,
    var pos: Vec2,
    val vel: Vec2,
    val damage: Float,
    val crit: Boolean,
    var lifeRemaining: Float,
    /** True for player/turret shots (they hit enemies). Enemy shots would be false. */
    val friendly: Boolean = false,
    val radius: Float = 5f,
)

/**
 * A player-placed defense (turret or barricade) snapped to a grid cell. Permanent for the run and
 * destructible — enemies blocked by it attack it. Turrets track an [aim] toward their current
 * target for the barrel.
 */
class Structure(
    val id: Int,
    val type: com.lifeops.app.game.content.StructureType,
    val col: Int,
    val row: Int,
    val pos: Vec2,
    var hp: Float,
    val maxHp: Float,
    var fireCooldown: Float = 0f,
    var aim: Vec2 = Vec2(1f, 0f),
    /** True for engine-deployed auto-turrets (the Turret artifact); false for player-placed defenses. */
    val artifactTurret: Boolean = false,
    /** Seconds of life left. [Float.POSITIVE_INFINITY] for permanent placed defenses; finite for
     *  auto-turrets, which expire when it runs out (DESIGN.md §4 — limited TTL). */
    var ttl: Float = Float.POSITIVE_INFINITY,
    /** Full lifespan the auto-turret was deployed with, for a fade-out as it ages (renderer only). */
    val maxTtl: Float = Float.POSITIVE_INFINITY,
) {
    val alive: Boolean get() = hp > 0f && ttl > 0f
}

class Pickup(
    val id: Int,
    var pos: Vec2,
    val kind: com.lifeops.app.game.core.PickupKind,
    val amount: Int,
    val radius: Float = 9f,
)
