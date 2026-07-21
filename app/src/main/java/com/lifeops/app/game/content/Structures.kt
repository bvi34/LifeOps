package com.lifeops.app.game.content

/**
 * Structures on the arena grid (the "yet another zombie defense" layer). Authored as data: cost, HP,
 * whether it blocks enemy movement, and — for turrets — its auto-fire stats. Two acquisition paths:
 *
 * - **Barricade** is [buildable] — bought with gold from the palette and placed on the grid (§7).
 * - **Turret** is *not* buildable: it is the [Artifacts.TURRET] combat-equipment artifact (§4/§5),
 *   auto-deployed by the engine near the player on a cooldown with a limited TTL, then expiring.
 *   Its stat rows here are the turret's base auto-weapon stats, boosted by AUTO-scope artifacts.
 *
 * Adding a defense is a row.
 */
enum class StructureType(
    val displayName: String,
    val cost: Int,
    val maxHp: Float,
    /** Whether enemies are blocked by (and attack) this structure. */
    val blocks: Boolean,
    /** Whether the player can buy + place this from the build palette (false → engine-deployed only). */
    val buildable: Boolean,
    // Turret auto-fire stats (zero/unused for non-turrets).
    val damage: Float = 0f,
    val fireRate: Float = 0f,
    val range: Float = 0f,
    val projectileSpeed: Float = 0f,
) {
    // Durability is measured in hits: a turret falls to one enemy blow, a barricade takes three.
    // The auto-turret does not block — it is fragile fire support that expires on its TTL, not a wall,
    // so it never boxes the player in.
    TURRET(
        displayName = "Turret",
        cost = 0,
        maxHp = 1f,
        blocks = false,
        buildable = false,
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
        buildable = true,
    );

    val isTurret: Boolean get() = fireRate > 0f
}
