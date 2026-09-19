package com.lifeops.app.game.content

import com.lifeops.app.game.core.ArtifactCategory
import com.lifeops.app.game.core.AttachTarget
import com.lifeops.app.game.core.Modifier
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatContribution

/**
 * The between-set draft shop (DESIGN.md §9 — "Phase 2 — Modifier draft shop"), authored as data
 * per invariant #4. Every entry is one **unlockable** thing the player buys with banked *Modifier
 * Budget* (the Personal-aspect resource — DESIGN.md §2). A purchase does two things:
 *
 *  1. It is granted to the *current* run immediately — a passive/equipment is added to the build, a
 *     gun swaps the aimed weapon, a mutator applies its run-scoped effect.
 *  2. It is unlocked **permanently** (persisted as a real record — DESIGN.md §9), so future runs get
 *     it back through the appropriate pool: passives/equipment rejoin the level-up draft, guns rejoin
 *     the loadout roster, mutators become loadout opt-ins.
 *
 * This is the fourth face of the one modifier system: artifacts attach to entities, challenge modes
 * to the director, overflow picks to the run — and store items are simply authored rows here. Adding
 * content (mines, arc thrower, drones, more skulls…) is appending an [Item], not writing engine code.
 */
object StoreCatalog {

    /** The four store tiers and their fixed Modifier-Budget price. Deliberately steep (§9). */
    enum class Category(val label: String, val cost: Int) {
        MODIFIER("Modifier", 25),   // run-wide mutators — the "crazy stuff" (Halo skulls)
        PASSIVE("Passive", 50),     // stat-support artifacts (splitter/reloader family)
        EQUIPMENT("Equipment", 100),// combat equipment (turret family)
        GUN("Gun", 150),            // new aimed weapons
    }

    /** One authored, buyable thing. Every item has a stable [id] used as its persisted unlock key. */
    sealed class Item {
        abstract val id: String
        abstract val name: String
        abstract val description: String
        abstract val category: Category

        /**
         * A passive or equipment artifact that joins the level-up draft pool (§5). The [modifier]'s
         * own [ArtifactCategory] decides whether it reads as Support or Equipment in the draft.
         */
        data class ArtifactItem(
            override val id: String,
            override val category: Category,
            val modifier: Modifier,
        ) : Item() {
            override val name get() = modifier.name
            override val description get() = modifier.description
        }

        /** A new aimed weapon. Bought → swaps the current weapon; owned → selectable in the loadout. */
        data class GunItem(
            override val id: String,
            override val name: String,
            override val description: String,
            val weapon: StartingWeapon,
        ) : Item() {
            override val category get() = Category.GUN
        }

        /**
         * A run-wide mutator (a "skull"). [playerBonuses] fold into the player build and
         * [directorBanes] onto the enemy director, so a mutator can be pure upside, pure downside, or
         * a trade — all expressed with the existing stat schema, no new engine paths.
         */
        data class MutatorItem(
            override val id: String,
            override val name: String,
            override val description: String,
            val playerBonuses: List<StatContribution> = emptyList(),
            val directorBanes: List<StatContribution> = emptyList(),
        ) : Item() {
            override val category get() = Category.MODIFIER
        }
    }

    // --- Passives (STAT_SUPPORT artifacts, §5). Pure stat math, 4 additive ranks each. ---------------
    private fun passive(id: String, name: String, description: String, stat: Stat, scope: Scope, perRank: Float, op: Op = Op.ADD_PERCENT, maxRank: Int = 4) =
        Item.ArtifactItem(
            id = id, category = Category.PASSIVE,
            modifier = Modifier(
                id = id, name = name, description = description,
                attachesTo = AttachTarget.ENTITY, maxRank = maxRank,
                rankContributions = List(maxRank) { StatContribution(stat, scope, op, perRank) },
                category = ArtifactCategory.STAT_SUPPORT,
            ),
        )

    /**
     * A passive whose ranks lift *different* stats in turn, so one artifact grows two axes at once
     * (the rank count is the number of rows). Still pure additive stat math (§5) — just a richer
     * shape than the single-stat [passive] above.
     */
    private fun multiPassive(id: String, name: String, description: String, contributions: List<StatContribution>) =
        Item.ArtifactItem(
            id = id, category = Category.PASSIVE,
            modifier = Modifier(
                id = id, name = name, description = description,
                attachesTo = AttachTarget.ENTITY, maxRank = contributions.size,
                rankContributions = contributions,
                category = ArtifactCategory.STAT_SUPPORT,
            ),
        )

    private val EAGLE_EYE = passive("eagle_eye", "Eagle Eye", "+5% crit chance per rank.",
        Stat.CRIT_CHANCE, Scope.AIMED, 0.05f, op = Op.FLAT)
    private val SOFT_POINT = passive("soft_point", "Soft Point", "+20% crit damage per rank.",
        Stat.CRIT_MULT, Scope.AIMED, 0.20f)
    private val LONG_BARREL = passive("long_barrel", "Long Barrel", "+12% range per rank.",
        Stat.RANGE, Scope.AIMED, 0.12f)
    private val SCAVENGER = passive("scavenger", "Scavenger", "+20% pickup radius per rank.",
        Stat.PICKUP_RADIUS, Scope.GLOBAL, 0.20f)
    private val QUICK_STUDY = passive("quick_study", "Quick Study", "+15% XP gain per rank.",
        Stat.XP_GAIN, Scope.GLOBAL, 0.15f)
    // Stats the baseline artifacts don't cover yet — fire rate and projectile speed on the aimed weapon.
    private val RAPID_FIRE = passive("rapid_fire", "Rapid Fire", "+12% fire rate per rank.",
        Stat.FIRE_RATE, Scope.AIMED, 0.12f)
    private val MUZZLE_VELOCITY = passive("muzzle_velocity", "Muzzle Velocity", "+15% projectile speed per rank.",
        Stat.PROJECTILE_SPEED, Scope.AIMED, 0.15f)

    // On-hit behaviour passives — the "real impact" tier (like Splitter, they change how a shot
    // behaves, not just its numbers). They stamp onto the aimed weapon's projectiles, so they
    // transform whatever gun is equipped (DESIGN.md §9). maxRank 3: each rank is a big swing.
    private val PENETRATION = passive("pierce", "Penetration", "Shots pass through +1 enemy per rank.",
        Stat.PIERCE, Scope.AIMED, 1f, op = Op.FLAT, maxRank = 3)
    private val RICOCHET = passive("ricochet", "Ricochet", "On hit, fires a fresh shot at another nearby enemy — +1 bounce per rank.",
        Stat.RICOCHET, Scope.AIMED, 1f, op = Op.FLAT, maxRank = 3)
    private val EXPLOSIVE_ROUNDS = passive("explosive", "Explosive Rounds", "Shots detonate on impact — +22 blast radius per rank.",
        Stat.EXPLOSION_RADIUS, Scope.AIMED, 22f, op = Op.FLAT, maxRank = 3)

    // Multi-axis passives — one artifact, two growing stats. Richer picks for a build to lean into.
    private val GUNSLINGER = multiPassive(
        "gunslinger", "Gunslinger", "Alternates +15% aimed damage and +15% fire rate each rank.",
        listOf(
            StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
            StatContribution(Stat.FIRE_RATE, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
            StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
            StatContribution(Stat.FIRE_RATE, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
        ),
    )
    private val PREDATOR = multiPassive(
        "predator", "Predator", "Alternates +8% crit chance and +30% crit damage each rank.",
        listOf(
            StatContribution(Stat.CRIT_CHANCE, Scope.AIMED, Op.FLAT, 0.08f),
            StatContribution(Stat.CRIT_MULT, Scope.AIMED, Op.ADD_PERCENT, 0.30f),
            StatContribution(Stat.CRIT_CHANCE, Scope.AIMED, Op.FLAT, 0.08f),
            StatContribution(Stat.CRIT_MULT, Scope.AIMED, Op.ADD_PERCENT, 0.30f),
        ),
    )
    private val QUARTERMASTER = multiPassive(
        "quartermaster", "Quartermaster", "Alternates +18% magazine and +15% reload speed each rank.",
        listOf(
            StatContribution(Stat.MAGAZINE, Scope.AIMED, Op.ADD_PERCENT, 0.18f),
            StatContribution(Stat.RELOAD_SPEED, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
            StatContribution(Stat.MAGAZINE, Scope.AIMED, Op.ADD_PERCENT, 0.18f),
            StatContribution(Stat.RELOAD_SPEED, Scope.AIMED, Op.ADD_PERCENT, 0.15f),
        ),
    )

    // --- Equipment (COMBAT_EQUIPMENT, §5). Reads OUTPOST_COUNT; the engine plants that many permanent
    // Sentry+Barricade emplacements near the player. Distinct from the always-available Turret artifact:
    // the Turret is mobile fire-support you draft for free; the Outpost is a lasting strongpoint you buy. --
    private val OUTPOST = Item.ArtifactItem(
        id = "outpost", category = Category.EQUIPMENT,
        modifier = Modifier(
            id = "outpost", name = "Outpost",
            description = "Rank 1 plants a permanent Sentry + Barricade emplacement near you; later ranks roll a random upgrade that buffs your whole defensive line.",
            attachesTo = AttachTarget.ENTITY, maxRank = 4,
            rankContributions = listOf(
                StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 1f), // rank 1: the emplacement
                StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // ranks 2-4: a rolled
                StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // upgrade from
                StatContribution(Stat.OUTPOST_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // EquipmentUpgrades.OUTPOST.
            ),
            category = ArtifactCategory.COMBAT_EQUIPMENT,
        ),
    )
    private val MINES = Item.ArtifactItem(
        id = "mines", category = Category.EQUIPMENT,
        modifier = Modifier(
            id = "mines", name = "Mines",
            description = "Rank 1 sows proximity mines that detonate on contact; later ranks roll a random mine upgrade.",
            attachesTo = AttachTarget.ENTITY, maxRank = 4,
            rankContributions = listOf(
                StatContribution(Stat.MINE_COUNT, Scope.GLOBAL, Op.FLAT, 1f), // rank 1: the first mine
                StatContribution(Stat.MINE_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // ranks 2-4: a rolled
                StatContribution(Stat.MINE_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // mine upgrade from
                StatContribution(Stat.MINE_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // EquipmentUpgrades.MINES.
            ),
            category = ArtifactCategory.COMBAT_EQUIPMENT,
        ),
    )
    private val DECOY = Item.ArtifactItem(
        id = "decoy", category = Category.EQUIPMENT,
        modifier = Modifier(
            id = "decoy", name = "Decoy",
            description = "Rank 1 plants a decoy that lures distant enemies off you; later ranks roll a random decoy upgrade.",
            attachesTo = AttachTarget.ENTITY, maxRank = 4,
            rankContributions = listOf(
                StatContribution(Stat.DECOY_COUNT, Scope.GLOBAL, Op.FLAT, 1f), // rank 1: the first decoy
                StatContribution(Stat.DECOY_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // ranks 2-4: a rolled
                StatContribution(Stat.DECOY_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // decoy upgrade from
                StatContribution(Stat.DECOY_COUNT, Scope.GLOBAL, Op.FLAT, 0f), // EquipmentUpgrades.DECOY.
            ),
            category = ArtifactCategory.COMBAT_EQUIPMENT,
        ),
    )

    // --- Guns (§4). Fully data-authored weapons; picked → swap, owned → loadout. -----------------------
    private val SMG = Item.GunItem(
        id = "gun_smg", name = "SMG",
        description = "A cheap bullet hose — very high fire rate, low damage, big magazine.",
        weapon = StartingWeapon.SMG,
    )
    private val HAND_CANNON = Item.GunItem(
        id = "gun_hand_cannon", name = "Hand Cannon",
        description = "A slow lob of small explosive rounds — shots detonate on impact and splash the cluster. Clears packs, tiny magazine.",
        weapon = StartingWeapon.HAND_CANNON,
    )

    // --- Modifiers / mutators (the "crazy stuff", §9). Run-wide trades on the shared stat schema. -----
    private val HORDE = Item.MutatorItem(
        id = "mut_horde", name = "Horde",
        description = "Double the enemies each wave — but +30% XP gain to feed on the swarm.",
        playerBonuses = listOf(StatContribution(Stat.XP_GAIN, Scope.GLOBAL, Op.ADD_PERCENT, 0.30f)),
        directorBanes = listOf(StatContribution(Stat.SPAWN_MULT, Scope.GLOBAL, Op.ADD_PERCENT, 1.0f)),
    )
    private val BERSERK = Item.MutatorItem(
        id = "mut_berserk", name = "Berserker",
        description = "+40% fire rate for you — but enemies move +25% faster.",
        playerBonuses = listOf(StatContribution(Stat.FIRE_RATE, Scope.AIMED, Op.ADD_PERCENT, 0.40f)),
        directorBanes = listOf(StatContribution(Stat.ENEMY_SPEED_MULT, Scope.GLOBAL, Op.ADD_PERCENT, 0.25f)),
    )

    /** The full catalog. Order is irrelevant — the store shuffles offers with the run RNG (§9). */
    val ITEMS: List<Item> = listOf(
        // Modifiers (25)
        HORDE, BERSERK,
        // Passives (50)
        EAGLE_EYE, SOFT_POINT, LONG_BARREL, SCAVENGER, QUICK_STUDY,
        RAPID_FIRE, MUZZLE_VELOCITY, GUNSLINGER, PREDATOR, QUARTERMASTER,
        PENETRATION, RICOCHET, EXPLOSIVE_ROUNDS,
        // Equipment (100)
        OUTPOST, MINES, DECOY,
        // Guns (150)
        SMG, HAND_CANNON,
    )

    fun byId(id: String): Item? = ITEMS.firstOrNull { it.id == id }

    /** The artifact [Modifier]s an [unlockedIds] set contributes to a run's level-up draft pool. */
    fun unlockedArtifacts(unlockedIds: Set<String>): List<Modifier> =
        ITEMS.filterIsInstance<Item.ArtifactItem>().filter { it.id in unlockedIds }.map { it.modifier }

    /** The guns an [unlockedIds] set makes selectable in the loadout (beyond the always-on baseline). */
    fun unlockedGuns(unlockedIds: Set<String>): List<StartingWeapon> =
        ITEMS.filterIsInstance<Item.GunItem>().filter { it.id in unlockedIds }.map { it.weapon }

    /** The mutators an [unlockedIds] set makes available as loadout opt-ins for future runs. */
    fun unlockedMutators(unlockedIds: Set<String>): List<Item.MutatorItem> =
        ITEMS.filterIsInstance<Item.MutatorItem>().filter { it.id in unlockedIds }
}
