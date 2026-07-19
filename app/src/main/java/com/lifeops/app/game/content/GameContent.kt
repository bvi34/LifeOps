package com.lifeops.app.game.content

import com.lifeops.app.game.core.AttachTarget
import com.lifeops.app.game.core.Modifier
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution

/**
 * All baseline content authored as data (DESIGN.md invariant #4). No behaviour here — baseline
 * artifacts do stat math only (§5); behaviour is Phase-2 shop territory. Adding a weapon, an
 * artifact rank, or an enemy is editing these tables, not the engine.
 */

/** The two player-aimed starting weapons (DESIGN.md §4). Base stat rows for each. */
enum class StartingWeapon(
    val displayName: String,
    val blurb: String,
    val baseStats: Map<Stat, Float>,
    /**
     * Gatling normalizes total DPS across projectile count (§4): more projectiles = more, smaller
     * hits at the same throughput. The engine divides per-hit damage by projectile count when this
     * is true, so buying projectile ranks buys coverage, not raw DPS.
     */
    val dpsNormalized: Boolean,
) {
    SNIPER(
        displayName = "Sniper",
        blurb = "Burst, precision, single-target. Deletes elites; weak vs trash.",
        baseStats = mapOf(
            Stat.DAMAGE to 34f,
            Stat.FIRE_RATE to 1.6f,
            Stat.PROJECTILES to 1f,
            Stat.PROJECTILE_SPEED to 560f,
            Stat.RANGE to 620f,
            Stat.CRIT_CHANCE to 0.15f,
            Stat.CRIT_MULT to 2.5f,
        ),
        dpsNormalized = false,
    ),
    GATLING(
        displayName = "Gatling",
        blurb = "Sustained stream. Total DPS normalized across projectiles — coverage, not throughput.",
        baseStats = mapOf(
            Stat.DAMAGE to 16f,
            Stat.FIRE_RATE to 7f,
            Stat.PROJECTILES to 1f,
            Stat.PROJECTILE_SPEED to 420f,
            Stat.RANGE to 460f,
            Stat.CRIT_CHANCE to 0.05f,
            Stat.CRIT_MULT to 2f,
        ),
        dpsNormalized = true,
    );

    fun baseStatBlock(): StatBlock = StatBlock(baseStats)
}

/**
 * Baseline artifact pool: exactly 4, each with 4 pure-additive stacking ranks (DESIGN.md §5).
 * Rolling a duplicate upgrades the rank. Balance warnings from the design doc are honoured:
 * +projectiles and +turret are secretly multiplicative, so their per-rank steps are smaller.
 */
object Artifacts {

    private fun ranks(stat: Stat, scope: Scope, perRank: Float, op: Op = Op.ADD_PERCENT, count: Int = 4) =
        List(count) { StatContribution(stat, scope, op, perRank) }

    val OVERCLOCK = Modifier(
        id = "overclock",
        name = "Overclock",
        description = "+10% aimed damage per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.DAMAGE, Scope.AIMED, 0.10f),
    )

    val AUTOLOADER = Modifier(
        id = "autoloader",
        name = "Autoloader",
        description = "+12% aimed fire rate per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.FIRE_RATE, Scope.AIMED, 0.12f),
    )

    // +1 projectile per rank is flat-additive to the count but multiplicative in effect (§5),
    // so it is deliberately the rarest kind of power — one artifact, capped at +4.
    val SPLITTER = Modifier(
        id = "splitter",
        name = "Splitter",
        description = "+1 aimed projectile per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.PROJECTILES, Scope.AIMED, 1f, op = Op.FLAT),
    )

    val ADRENALINE = Modifier(
        id = "adrenaline",
        name = "Adrenaline",
        description = "+8% move speed per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.MOVE_SPEED, Scope.GLOBAL, 0.08f),
    )

    val ALL: List<Modifier> = listOf(OVERCLOCK, AUTOLOADER, SPLITTER, ADRENALINE)

    fun byId(id: String): Modifier? = ALL.firstOrNull { it.id == id }
}

/**
 * Enemy archetypes (DESIGN.md §3/§7). Same stat schema as the player, so a future Mob-Boss mode
 * can hand an enemy an artifact through the identical attachment code path.
 */
enum class EnemyType(
    val displayName: String,
    /** Durability. Baseline trash is tuned to die to a single shot ("one hit"). */
    val maxHealth: Float,
    val moveSpeed: Float,
    /** Hearts removed from the player (and hits dealt to structures) on a contact/attack. */
    val contactHits: Int,
    val radius: Float,
    val xpValue: Int,
    val goldValue: Int,
    /** Endless tier at which this type joins its pool (0 = from the start). For trash, this gates
     *  the spawn roll; for bosses, it gates the cumulative boss roster on the final wave. */
    val unlockTier: Int = 0,
    /** Relative likelihood of being chosen from the unlocked trash pool. */
    val spawnWeight: Int = 1,
    /** Boss-kind: drops guaranteed loot, shows on the boss bar, spawned only on the final wave. */
    val isBoss: Boolean = false,
    /** How many of this boss spawn per final wave (a "swarm" boss spawns several). */
    val bossCount: Int = 1,
    // Ranged enemies (Spitter) fire bursts at "tower rate"; melee types leave these zero.
    val fireRate: Float = 0f,       // intra-burst cadence
    val burstCount: Int = 1,        // shots per burst
    val burstCooldown: Float = 0f,  // recovery after a burst
    val range: Float = 0f,
    val projectileSpeed: Float = 0f,
) {
    // Trash: cheap, fast, swarms — dies to one shot. Deals one heart.
    SHAMBLER("Shambler", maxHealth = 8f, moveSpeed = 48f, contactHits = 1, radius = 13f, xpValue = 3, goldValue = 1,
        unlockTier = 0, spawnWeight = 10),
    // Elite: tanky, takes several shots. Still one heart on contact.
    HUSK("Husk", maxHealth = 70f, moveSpeed = 34f, contactHits = 1, radius = 18f, xpValue = 10, goldValue = 4,
        unlockTier = 0, spawnWeight = 3),
    // Ranged (tier 2+): hangs back and fires bursts — three quick spits, then a long recovery.
    SPITTER("Spitter", maxHealth = 24f, moveSpeed = 30f, contactHits = 1, radius = 15f, xpValue = 8, goldValue = 3,
        unlockTier = 1, spawnWeight = 3, fireRate = 6f, burstCount = 3, burstCooldown = 2.4f, range = 320f, projectileSpeed = 300f),
    // Rusher (tier 3+): fast, fragile — punishes standing still.
    RUSHER("Rusher", maxHealth = 6f, moveSpeed = 92f, contactHits = 1, radius = 11f, xpValue = 5, goldValue = 1,
        unlockTier = 2, spawnWeight = 5),
    // Brute (tier 4+): a slow mini-boss that hits for two hearts and soaks a magazine.
    BRUTE("Brute", maxHealth = 150f, moveSpeed = 26f, contactHits = 2, radius = 22f, xpValue = 22, goldValue = 6,
        unlockTier = 3, spawnWeight = 2),

    // --- Bosses (final wave). The roster is cumulative: every unlocked boss shows up each loop. ---
    // Tier 1: the original heavy melee sponge. A contact costs three hearts.
    ABOMINATION("Abomination", maxHealth = 900f, moveSpeed = 28f, contactHits = 3, radius = 34f, xpValue = 80, goldValue = 40,
        isBoss = true, unlockTier = 0),
    // Tier 2: a ranged boss that fires without pause — a relentless stream, not bursts.
    SPITTER_BOSS("Spitter Boss", maxHealth = 620f, moveSpeed = 26f, contactHits = 3, radius = 30f, xpValue = 70, goldValue = 34,
        isBoss = true, unlockTier = 1, fireRate = 5f, burstCount = 100000, burstCooldown = 0f, range = 400f, projectileSpeed = 320f),
    // Tier 3: a swarm of fast, dangerous rusher-bosses that arrive together.
    RUSHER_BOSS("Rusher Swarm", maxHealth = 240f, moveSpeed = 80f, contactHits = 2, radius = 22f, xpValue = 40, goldValue = 16,
        isBoss = true, unlockTier = 2, bossCount = 3),
    ;

    val isRanged: Boolean get() = fireRate > 0f
}
