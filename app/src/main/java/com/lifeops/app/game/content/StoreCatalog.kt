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
    private fun passive(id: String, name: String, description: String, stat: Stat, scope: Scope, perRank: Float, op: Op = Op.ADD_PERCENT) =
        Item.ArtifactItem(
            id = id, category = Category.PASSIVE,
            modifier = Modifier(
                id = id, name = name, description = description,
                attachesTo = AttachTarget.ENTITY, maxRank = 4,
                rankContributions = List(4) { StatContribution(stat, scope, op, perRank) },
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

    // --- Equipment (COMBAT_EQUIPMENT, §5). Reads TURRET_COUNT; the engine deploys that many. ---------
    private val SENTRY_ARRAY = Item.ArtifactItem(
        id = "sentry_array", category = Category.EQUIPMENT,
        modifier = Modifier(
            id = "sentry_array", name = "Sentry Array",
            description = "Rank 1 deploys an auto-turret; later ranks roll a random turret upgrade.",
            attachesTo = AttachTarget.ENTITY, maxRank = 4,
            rankContributions = listOf(
                StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 1f), // rank 1: the turret itself
                StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // ranks 2-4: a rolled
                StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // equipment upgrade,
                StatContribution(Stat.TURRET_COUNT, Scope.AUTO, Op.FLAT, 0f), // not a fixed row.
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
        description = "A slow, brutal single shot — huge damage, tiny magazine.",
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
        // Equipment (100)
        SENTRY_ARRAY,
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
