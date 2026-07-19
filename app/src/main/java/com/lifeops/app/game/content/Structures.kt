package com.lifeops.app.game.content

/**
 * Player-buildable defenses (the "yet another zombie defense" layer). Authored as data: cost, HP,
 * whether it blocks enemy movement, and — for turrets — its auto-fire stats. Placed on the arena
 * grid, permanent for the run, and destructible (enemies attack them). Adding a defense is a row.
 */
enum class StructureType(
    val displayName: String,
    val cost: Int,
    val maxHp: Float,
    /** Whether enemies are blocked by (and attack) this structure. */
    val blocks: Boolean,
    // Turret auto-fire stats (zero/unused for non-turrets).
    val damage: Float = 0f,
    val fireRate: Float = 0f,
    val range: Float = 0f,
    val projectileSpeed: Float = 0f,
) {
    // Durability is measured in hits now: a turret falls to one enemy blow, a barricade takes three.
    TURRET(
        displayName = "Turret",
        cost = 25,
        maxHp = 1f,
        blocks = true,
        damage = 14f,
        fireRate = 3f,
        range = 260f,
        projectileSpeed = 460f,
    ),
    BARRICADE(
        displayName = "Barricade",
        cost = 10,
        maxHp = 3f,
        blocks = true,
    );

    val isTurret: Boolean get() = fireRate > 0f
}
