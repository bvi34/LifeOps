package com.lifeops.app.game.content

import com.lifeops.app.game.core.ArtifactCategory
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

/** The player-aimed starting weapons (DESIGN.md §4). Base stat rows + firing character for each. */
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
    /**
     * When true the weapon's shots never expire from travel (the Sniper "never reaches end of
     * range", §4) — they fly until they hit something or leave the arena, and auto-aim locks on
     * anywhere on the field. When false, shots die at [Stat.RANGE] (Gatling medium, Shotgun short).
     */
    val unlimitedRange: Boolean,
    /** Fan angle (radians) across a multi-projectile shot. Shotgun spreads wide; the rest stay tight. */
    val spread: Float,
    /** Base rounds per magazine before a reload is needed. One trigger-pull spends one round. This is
     *  the base of [Stat.MAGAZINE], which the Extended Mag artifact raises. */
    val magazineSize: Int,
    /** Base seconds to reload an empty magazine, before the [Stat.RELOAD_SPEED] multiplier (§4). */
    val reloadSeconds: Float,
    /**
     * Spin-up (DESIGN.md §4). When [spinUpAccel] > 0 the fire rate ramps during continuous fire: it
     * starts at [spinUpFloor] shots/s and climbs [spinUpAccel] shots/s for every second engaged, up
     * to the weapon's [Stat.FIRE_RATE] ceiling, and resets the moment fire stops (the Gatling's
     * wind-up). Zero accel = a flat fire rate (Sniper, Shotgun).
     */
    val spinUpFloor: Float = 0f,
    val spinUpAccel: Float = 0f,
    /**
     * Baseline weapons are always in the loadout; unlockable ones only appear once the player has
     * bought them from the between-set store (DESIGN.md §9). A store gun swaps the run's aimed weapon
     * on purchase and joins the loadout roster for future runs. See [com.lifeops.app.game.content.StoreCatalog].
     */
    val unlockable: Boolean = false,
) {
    SNIPER(
        displayName = "Sniper",
        blurb = "Burst, precision, single-target. Unlimited range — shots never fall short. Deletes elites; weak vs trash.",
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
        unlimitedRange = true,
        spread = 0.28f,
        magazineSize = 5,
        reloadSeconds = 1.7f,
    ),
    GATLING(
        displayName = "Gatling",
        blurb = "Sustained stream, medium range — bullets fizzle out past their reach. Winds up: fire rate climbs the longer you hold fire, resets when you stop. DPS normalized across projectiles.",
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
        unlimitedRange = false,
        spread = 0.28f,
        magazineSize = 60,
        reloadSeconds = 2.3f,
        // Winds up: 1 shot/s from a standstill, +2 shots/s for every second of sustained fire, up to
        // the 7/s ceiling above (~3s to spin up); resets the instant it stops firing.
        spinUpFloor = 1f,
        spinUpAccel = 2f,
    ),
    SHOTGUN(
        displayName = "Shotgun",
        blurb = "A short-range cone of pellets — devastating up close, useless at distance. Each pellet hits full.",
        baseStats = mapOf(
            Stat.DAMAGE to 11f,
            Stat.FIRE_RATE to 1.3f,
            Stat.PROJECTILES to 5f,
            Stat.PROJECTILE_SPEED to 380f,
            Stat.RANGE to 210f,
            Stat.CRIT_CHANCE to 0.08f,
            Stat.CRIT_MULT to 2f,
        ),
        dpsNormalized = false,
        unlimitedRange = false,
        spread = 0.85f,
        magazineSize = 6,
        reloadSeconds = 1.9f,
    ),

    // --- Store-unlockable guns (DESIGN.md §9). Authored purely as data, same as the baseline three;
    //     they only reach the loadout/level-up once bought from the between-set store. ---
    SMG(
        displayName = "SMG",
        blurb = "A cheap bullet hose — very high fire rate, low per-hit damage, big magazine. Medium range; melts trash, chews reloads.",
        baseStats = mapOf(
            Stat.DAMAGE to 9f,
            Stat.FIRE_RATE to 11f,
            Stat.PROJECTILES to 1f,
            Stat.PROJECTILE_SPEED to 440f,
            Stat.RANGE to 400f,
            Stat.CRIT_CHANCE to 0.04f,
            Stat.CRIT_MULT to 2f,
        ),
        dpsNormalized = false,
        unlimitedRange = false,
        spread = 0.14f,
        magazineSize = 40,
        reloadSeconds = 2.1f,
        unlockable = true,
    ),
    HAND_CANNON(
        displayName = "Hand Cannon",
        blurb = "A slow, brutal single shot — huge damage, tiny magazine, punishing reload. Rewards aim over spray.",
        baseStats = mapOf(
            Stat.DAMAGE to 72f,
            Stat.FIRE_RATE to 1.1f,
            Stat.PROJECTILES to 1f,
            Stat.PROJECTILE_SPEED to 520f,
            Stat.RANGE to 500f,
            Stat.CRIT_CHANCE to 0.2f,
            Stat.CRIT_MULT to 2.5f,
        ),
        dpsNormalized = false,
        unlimitedRange = false,
        spread = 0.2f,
        magazineSize = 4,
        reloadSeconds = 2.0f,
        unlockable = true,
    );

    // Magazine lives in the stat block (so Extended Mag can scale it) but its base is this weapon
    // field — inject it here rather than duplicating the number in every baseStats map.
    fun baseStatBlock(): StatBlock = StatBlock(baseStats + (Stat.MAGAZINE to magazineSize.toFloat()))
}

/**
 * The level-up draft pool, authored as data (DESIGN.md §5). Two faces (DESIGN.md §5 / [ArtifactCategory]):
 *
 * - **Stat support** — pure stat math on the weapon/entity you already have (Overclock, Autoloader,
 *   Splitter, Adrenaline). Each has 4 pure-additive stacking ranks.
 * - **Combat equipment** — deploys an automatic weapon (the Turret). Its ranks add [Stat.TURRET_COUNT]
 *   and the engine keeps that many auto-turrets running; +turret is secretly multiplicative (§5), so
 *   it stacks fewer ranks than the flat-% supports.
 *
 * Rolling a duplicate upgrades the rank rather than offering a dead pick.
 */
object Artifacts {

    private fun ranks(stat: Stat, scope: Scope, perRank: Float, op: Op = Op.ADD_PERCENT, count: Int = 4) =
        List(count) { StatContribution(stat, scope, op, perRank) }

    // --- Stat support ---------------------------------------------------------------------------

    val OVERCLOCK = Modifier(
        id = "overclock",
        name = "Overclock",
        description = "+10% aimed damage per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.DAMAGE, Scope.AIMED, 0.10f),
        category = ArtifactCategory.STAT_SUPPORT,
    )

    // Reload artifact (§4): each rank speeds the magazine reload (auto on empty, or on demand). The
    // effective reload time is the weapon's base reload divided by this RELOAD_SPEED multiplier.
    val AUTOLOADER = Modifier(
        id = "autoloader",
        name = "Autoloader",
        description = "+20% reload speed per rank — shorter reloads.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.RELOAD_SPEED, Scope.AIMED, 0.20f),
        category = ArtifactCategory.STAT_SUPPORT,
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
        category = ArtifactCategory.STAT_SUPPORT,
    )

    val ADRENALINE = Modifier(
        id = "adrenaline",
        name = "Adrenaline",
        description = "+8% move speed per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.MOVE_SPEED, Scope.GLOBAL, 0.08f),
        category = ArtifactCategory.STAT_SUPPORT,
    )

    // Extended Mag (§4): +20% magazine capacity per rank, so you fire longer between reloads.
    val EXTENDED_MAG = Modifier(
        id = "extended_mag",
        name = "Extended Mag",
        description = "+20% magazine capacity per rank.",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = ranks(Stat.MAGAZINE, Scope.AIMED, 0.20f),
        category = ArtifactCategory.STAT_SUPPORT,
    )

    // --- Combat equipment -----------------------------------------------------------------------

    // The auto-turret (DESIGN.md §4/§5). Rank 1 deploys one auto-turret; ranks 2-4 each roll a
    // *random* turret improvement from [EquipmentUpgrades.TURRET] (damage / fire rate / projectiles /
    // range / an extra turret), so the equipment grows a different way each run. The modifier only
    // carries the rank-1 turret grant (AUTO scope); the rolled upgrades are applied by the engine,
    // which is why ranks 2-4 add nothing here. AUTO scope: turret stats, not the aimed weapon.
    val TURRET = Modifier(
        id = "turret",
        name = "Turret",
        description = "Rank 1 deploys an auto-turret; later ranks roll a random turret upgrade (damage, fire rate, projectiles…).",
        attachesTo = AttachTarget.ENTITY,
        maxRank = 4,
        rankContributions = listOf(
            StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 1f), // rank 1: the turret itself
            StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // ranks 2-4: power comes from
            StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // a rolled equipment upgrade,
            StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // not a fixed rank row.
        ),
        category = ArtifactCategory.COMBAT_EQUIPMENT,
    )

    val ALL: List<Modifier> = listOf(OVERCLOCK, AUTOLOADER, SPLITTER, ADRENALINE, EXTENDED_MAG, TURRET)

    /** The draft pool split by face, for the codex and any category-aware UI. */
    val COMBAT_EQUIPMENT: List<Modifier> = ALL.filter { it.category == ArtifactCategory.COMBAT_EQUIPMENT }
    val STAT_SUPPORT: List<Modifier> = ALL.filter { it.category == ArtifactCategory.STAT_SUPPORT }

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
    /** Independent per-kill drop chances. Trash stays stingy; elites pay out more (bosses always). */
    val xpDropChance: Float = 0.25f,
    val goldDropChance: Float = 0.05f,
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
    // Trash: cheap, fast, swarms. Tuned to 20 HP so the Gatling's base 16-damage shot needs two hits
    // (a Sniper still one-shots it). Deals one heart. Stingy drops (25% / 5%). Every other enemy's HP
    // is pulled up by the same ×2.5 so the difficulty curve keeps its shape.
    SHAMBLER("Shambler", maxHealth = 20f, moveSpeed = 48f, contactHits = 1, radius = 13f, xpValue = 3, goldValue = 1,
        unlockTier = 0, spawnWeight = 10),
    // Elite: tanky, takes several shots. Still one heart on contact. Pays out more often.
    HUSK("Husk", maxHealth = 175f, moveSpeed = 34f, contactHits = 1, radius = 18f, xpValue = 10, goldValue = 4,
        xpDropChance = 0.6f, goldDropChance = 0.3f, unlockTier = 0, spawnWeight = 3),
    // Ranged (tier 2+): hangs back and fires bursts — three quick spits, then a long recovery.
    SPITTER("Spitter", maxHealth = 60f, moveSpeed = 30f, contactHits = 1, radius = 15f, xpValue = 8, goldValue = 3,
        xpDropChance = 0.6f, goldDropChance = 0.3f,
        unlockTier = 1, spawnWeight = 3, fireRate = 6f, burstCount = 3, burstCooldown = 2.4f, range = 320f, projectileSpeed = 300f),
    // Rusher (tier 3+): fast, fragile trash — punishes standing still. Stingy like the Shambler.
    RUSHER("Rusher", maxHealth = 15f, moveSpeed = 92f, contactHits = 1, radius = 11f, xpValue = 5, goldValue = 1,
        unlockTier = 2, spawnWeight = 5),
    // Brute (tier 4+): a slow mini-boss that hits for two hearts and soaks a magazine. Rich drops.
    BRUTE("Brute", maxHealth = 375f, moveSpeed = 26f, contactHits = 2, radius = 22f, xpValue = 22, goldValue = 6,
        xpDropChance = 0.85f, goldDropChance = 0.5f, unlockTier = 3, spawnWeight = 2),

    // --- Bosses (final wave). The roster is cumulative: every unlocked boss shows up each loop. ---
    // Tier 1: the original heavy melee sponge. A contact costs three hearts.
    ABOMINATION("Abomination", maxHealth = 2250f, moveSpeed = 28f, contactHits = 3, radius = 34f, xpValue = 80, goldValue = 40,
        isBoss = true, unlockTier = 0),
    // Tier 2: a ranged boss that fires without pause — a relentless stream, not bursts.
    SPITTER_BOSS("Spitter Boss", maxHealth = 1550f, moveSpeed = 26f, contactHits = 3, radius = 30f, xpValue = 70, goldValue = 34,
        isBoss = true, unlockTier = 1, fireRate = 5f, burstCount = 100000, burstCooldown = 0f, range = 400f, projectileSpeed = 320f),
    // Tier 3: a swarm of fast, dangerous rusher-bosses that arrive together.
    RUSHER_BOSS("Rusher Swarm", maxHealth = 600f, moveSpeed = 80f, contactHits = 2, radius = 22f, xpValue = 40, goldValue = 16,
        isBoss = true, unlockTier = 2, bossCount = 3),
    ;

    val isRanged: Boolean get() = fireRate > 0f
}
