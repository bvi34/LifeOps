package com.lifeops.app.game.content

/**
 * Structures on the arena grid (the "yet another zombie defense" layer). Authored as data: cost, HP,
 * whether it blocks enemy movement + shots, and — for turrets — its auto-fire stats. Three kinds,
 * two acquisition paths:
 *
 * - **Barricade** — bought with gold ([buildable]), a blocking wall that stops enemies and soaks
 *   enemy fire (§7).
 * - **Sentry** — bought with gold ([buildable]), a *static* auto-turret: place it and it stays put,
 *   blocks, and fires on its own fixed stats (independent of your build).
 * - **Turret** — *not* buildable: it is the [Artifacts.TURRET] combat-equipment artifact (§4/§5),
 *   auto-deployed near the player on a cooldown with a limited TTL, then expiring. Non-blocking
 *   fire support; its stats are the turret base rows boosted by AUTO-scope artifacts.
 *
 * The **Outpost** shop equipment (§9) plants permanent Sentry + Barricade pairs near the player from
 * the engine, so both also arrive engine-owned (no gold cost); its upgrades lift every Sentry/Barricade
 * you own (SENTRY_DAMAGE / SENTRY_FIRE_RATE / BARRICADE_THORNS), gold-built ones included.
 *
 * Adding a defense is a row.
 */
enum class StructureType(
    val displayName: String,
    val cost: Int,
    val maxHp: Float,
    /**
     * Whether this structure's cell stops enemy *fire* (a true wall soaks incoming shots). Enemy
     * *movement* is blocked by every structure regardless — an enemy stops and smashes any structure
     * in its path (see RunEngine.moveEnemyToward); this flag is now only about blocking shots.
     */
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
    // The auto-turret doesn't stop enemy fire ([blocks] = false) and expires on its TTL — but an
    // enemy in contact still stops to smash it (it dies in one blow, so it's a speed-bump, not a wall).
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
    // Static gold turret: permanent, blocks, and shoots on its own stats — doesn't follow the player.
    SENTRY(
        displayName = "Sentry",
        cost = 25,
        maxHp = 2f,
        blocks = true,
        buildable = true,
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
    ),
    // The Decoy equipment (DESIGN.md §9): engine-deployed, non-blocking, no fire. A lure that soaks
    // aggro — enemies path to it and smash it. Deployed with build-scaled HP (DECOY_HP), not this
    // base, which is only a floor. Persists until destroyed, then the equipment redeploys it.
    DECOY(
        displayName = "Decoy",
        cost = 0,
        maxHp = 5f,
        blocks = false,
        buildable = false,
    );

    val isTurret: Boolean get() = fireRate > 0f
}
