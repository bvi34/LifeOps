package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.Vec2

/**
 * Live run entities. These are mutable and stepped in place — at baseline entity counts a
 * Compose Canvas handles them fine (DESIGN.md §11), and per-frame allocation churn is the thing
 * to avoid on Android. Rendering reads an immutable snapshot ([RunSnapshot]).
 */

class Player(
    var pos: Vec2,
    val weapon: StartingWeapon,
    val held: MutableList<HeldModifier> = mutableListOf(),
    var health: Float,
    var maxHealth: Float,
    var level: Int = 1,
    var xp: Float = 0f,
    var xpToNext: Float = 12f,
    var gold: Int = 0,
    var fireCooldown: Float = 0f,
) {
    /**
     * Rebuild the stat block from the weapon base + every held modifier's contributions. Called
     * whenever the build changes (level-up pick), not per frame. GLOBAL and the weapon's own scope
     * are what the aimed shot reads.
     */
    fun buildStats(): StatBlock {
        val block = weapon.baseStatBlock()
        held.forEach { block.addAll(it.contributions()) }
        return block
    }

    fun aimedDamage(stats: StatBlock): Float = stats.resolve(Stat.DAMAGE, Scope.AIMED)
    fun fireRate(stats: StatBlock): Float = stats.resolve(Stat.FIRE_RATE, Scope.AIMED).coerceAtLeast(0.1f)
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
    val kind: EntityKind = if (type == EnemyType.ABOMINATION) EntityKind.BOSS else EntityKind.ENEMY,
) {
    val alive: Boolean get() = health > 0f
}

class Projectile(
    val id: Int,
    val ownerId: Int,
    var pos: Vec2,
    val vel: Vec2,
    val damage: Float,
    val crit: Boolean,
    var lifeRemaining: Float,
    val radius: Float = 5f,
)

class Pickup(
    val id: Int,
    var pos: Vec2,
    val kind: com.lifeops.app.game.core.PickupKind,
    val amount: Int,
    val radius: Float = 9f,
)
