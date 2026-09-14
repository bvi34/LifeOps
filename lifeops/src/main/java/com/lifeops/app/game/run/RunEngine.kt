package com.lifeops.app.game.run

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.SetBonuses
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.content.EquipmentUpgrades
import com.lifeops.app.game.content.TempBoosts
import com.lifeops.app.game.core.Modifier
import com.lifeops.app.game.core.EffectResolver
import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.EventBus
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.HeldModifier
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.RunSeed
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution
import com.lifeops.app.game.core.Vec2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pure, deterministic run simulation. Given a fixed [RunConfig] and the same [RunInput]
 * sequence it produces identical frames on JVM and device — the ViewModel only supplies timing
 * and reads snapshots. Every combat moment goes through the [EventBus]; every effect goes through
 * the [EffectResolver] budget (DESIGN.md invariants #2, #3). No android.* here.
 *
 * The fight is an open single-screen arena (Geometry Wars) you widen with gold (§7): endless waves
 * of beelining enemies, multiple bosses on the final wave, looping into higher tiers on a clear.
 *
 * **Where the rest of it is.** This file holds the run's state, its public API, and the order a
 * frame resolves in — read [step] and you have the whole simulation's table of contents. Each system
 * it calls lives in its own file as extensions on this class: [RunMovement], [RunWaves],
 * [RunWeaponFire], [RunCollisions], [RunPickups] and [RunStorePhase] (the file names, not types).
 *
 * Extensions rather than system classes because one frame is one pass over one set of mutable state:
 * handing each system its own copy of the arena would be wrong, and handing it a reference back to
 * the engine to reach the real one buys indirection and no isolation. The cost of the split is that
 * state the systems touch is `internal` rather than `private` — visible inside `:lifeops`, and no
 * further.
 */
class RunEngine(
    val config: RunConfig,
    val bus: EventBus = EventBus(),
    internal val resolver: EffectResolver = EffectResolver(),
) {
    /** The open, expandable play space. */
    val arena = ArenaState()
    internal val rng = RunSeed(config.seed)

    val player = Player(
        pos = arena.center,
        weapon = config.weapon,
        hits = config.maxHits,
        maxHits = config.maxHits,
        gold = config.startingGold,
        ammo = config.weapon.magazineSize, // start with a full magazine
    )

    val enemies = ArrayList<Enemy>()
    val projectiles = ArrayList<Projectile>()
    val pickups = ArrayList<Pickup>()
    val effects = ArrayList<RunEffect>()
    val structures = ArrayList<Structure>()
    /** Sown proximity mines from the Mines equipment (DESIGN.md §9). */
    val mines = ArrayList<Mine>()
    /** Grid-cell occupancy for placed structures, keyed by [cellKey]. One structure per cell. */
    internal val occupancy = HashMap<Int, Structure>()
    internal val worldCols = (arena.worldSize.x / arena.cellSize).toInt().coerceAtLeast(1)

    internal fun colOf(x: Float) = (x / arena.cellSize).toInt()
    internal fun rowOf(y: Float) = (y / arena.cellSize).toInt()
    internal fun cellKey(col: Int, row: Int) = row * worldCols + col
    internal fun structureAt(pos: Vec2): Structure? = occupancy[cellKey(colOf(pos.x), rowOf(pos.y))]

    var status: RunStatus = RunStatus.RUNNING
        internal set
    var score: Long = 0L
        internal set
    var wave: Int = 0
        internal set
    /** Endless-mode loop counter: every full waves+boss clear bumps it, scaling all enemies (§7). */
    var tier: Int = 0
        internal set
    /** How many times the player has bought back into this run after a defeat (DESIGN.md §7). */
    var revives: Int = 0
        internal set

    internal var stats: StatBlock = player.buildStats()
    /** The auto-turret's effective stats: its base auto-weapon rows + the player's AUTO-scope build. */
    internal var turretStats: StatBlock = buildTurretStats()
    internal var nextId = 1
    /** Cooldown between auto-turret deployments, so the Turret artifact drips them in (not all at once). */
    internal var turretDeployTimer = 0f
    /** Cooldown between mine deployments, so the Mines equipment sows them in over time. */
    internal var mineDeployTimer = 0f
    /** Cooldown between decoy deployments, so the Decoy equipment replants them as they're torn down. */
    internal var decoyDeployTimer = 0f
    /** Cooldown between Outpost deployments — permanent emplacements drip in slowly, one at a time. */
    internal var outpostDeployTimer = 0f

    /** Hosts every enemy-modifying decision for this run (DESIGN.md §8): challenge-mode modifiers,
     *  remap tables, and per-enemy artifacts. Standard runs install [ChallengeMode.NONE]. */
    val director = Director(bus)

    // Wave director state.
    internal var spawnedThisWave = 0
    internal var toSpawnThisWave = 0
    internal var spawnTimer = 0f
    internal var breatherTimer = 0f
    internal var bossTimer = 0f
    internal var bossesToSpawn = 0
    internal var bossesSpawned = 0
    internal var pendingBosses: List<EnemyType> = emptyList()

    internal var levelUpOptions: List<LevelUpOption> = emptyList()
    internal var setBonusOptions: List<SetBonusOption> = emptyList()
    internal var overflowOptions: List<OverflowOption> = emptyList()
    internal var storeOptions: List<StoreOffer> = emptyList()

    /** Store items owned this run (DESIGN.md §9): the persisted unlocks, plus anything bought at a set
     *  boundary so far. Seeds the level-up draft pool and gates the store from re-offering owned items. */
    internal val runUnlockedIds: MutableSet<String> = config.unlockedIds.toMutableSet()

    /** The live aimed weapon — a store gun can swap it mid-run, so read it, never [config].weapon. */
    internal val weapon: StartingWeapon get() = player.weapon

    init {
        director.configure(config.challengeMode, stats)
        // Loadout-toggled mutators the player owns (DESIGN.md §9) apply from the first frame.
        config.activeMutatorIds.forEach { id ->
            (StoreCatalog.byId(id) as? StoreCatalog.Item.MutatorItem)?.let { applyMutator(it) }
        }
        beginWave(0)
    }

    internal fun rebuildStats() {
        stats = player.buildStats()
        turretStats = buildTurretStats()
        // Keep the Director tracking the player's build so Mirror inflates in lockstep (§8).
        director.rebuild(stats)
    }

    /**
     * The auto-turret's effective stats (DESIGN.md §3/§4): the turret's own base auto-weapon rows,
     * boosted by the player's AUTO-scope build. Resolving at [Scope.AUTO] reads AUTO + GLOBAL rows,
     * so equipment artifacts lift the turret while the aimed weapon's AIMED rows stay inert on it.
     */
    internal fun buildTurretStats(): StatBlock {
        val t = StructureType.TURRET
        val block = StatBlock(
            mapOf(
                Stat.DAMAGE to t.damage,
                Stat.FIRE_RATE to t.fireRate,
                Stat.RANGE to t.range,
                Stat.PROJECTILE_SPEED to t.projectileSpeed,
            )
        )
        player.held.forEach { block.addAll(it.contributions()) }
        player.runBonuses.forEach { block.add(it) }
        player.tempBuffs.forEach { block.add(it.contribution) }
        player.equipmentUpgrades.forEach { block.add(it) } // rolled turret upgrades (Turret ranks 2-4)
        return block
    }

    // --- Public API -------------------------------------------------------------------------

    /** Advance the simulation by [dt] seconds. No-op while paused on a level-up, or once over. */
    fun step(dt: Float, input: RunInput) {
        if (status != RunStatus.RUNNING) return
        val clamped = dt.coerceIn(0f, 0.05f) // guard against a stalled frame integrating a huge dt
        resolver.beginFrame()

        movePlayer(clamped, input)
        updateWaves(clamped)
        moveEnemies(clamped)
        updateEnemyFire(clamped)
        updateSpawners(clamped)
        updateAim(input)
        fireWeapon(clamped, input)
        updateArtifactTurrets(clamped)
        updateMines(clamped)
        updateDecoys(clamped)
        updateOutpost(clamped)
        updateStructures(clamped)
        moveProjectiles(clamped)
        resolveEnemyProjectileBlocks()
        resolveProjectileHits()
        resolveContact(clamped)
        updatePickups(clamped)
        ageVisuals(clamped)

        if (player.hits <= 0) endRun(victory = false)
    }

    /**
     * Place a defense at the grid cell containing [worldPos], spending its gold cost. Rejected if
     * the cell is outside the active arena, already occupied, on the player's own cell, or
     * unaffordable. Returns whether it was placed.
     */
    fun placeStructure(type: StructureType, worldPos: Vec2): Boolean {
        if (status != RunStatus.RUNNING) return false
        if (!type.buildable) return false // turrets are the auto-deployed Turret artifact, not a gold-buy
        val cost = buildCost(type)
        if (player.gold < cost) return false
        val col = colOf(worldPos.x)
        val row = rowOf(worldPos.y)
        val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
        if (!arena.contains(center)) return false
        if (occupancy.containsKey(cellKey(col, row))) return false
        if (col == colOf(player.pos.x) && row == rowOf(player.pos.y)) return false // don't box yourself in
        player.gold -= cost
        val s = Structure(
            id = nextId++, type = type, col = col, row = row, pos = center,
            hp = type.maxHp, maxHp = type.maxHp,
        )
        structures.add(s)
        occupancy[cellKey(col, row)] = s
        return true
    }

    /** Current gold cost of [type]. (Flat — the stingy drop table is what tunes the economy.) */
    fun buildCost(type: StructureType): Int = type.cost

    /**
     * Widen the arena a stage for escalating gold (the "unlock to widen the space", §7). No-op if
     * already at max size or the player can't afford it. Returns whether the expansion happened.
     */
    fun buyExpansion(): Boolean {
        if (status != RunStatus.RUNNING) return false
        val cost = arena.nextCost() ?: return false
        if (player.gold < cost) return false
        player.gold -= cost
        arena.expand()
        return true
    }

    /** Apply the chosen level-up option and resume. Ignored unless currently paused on level-up. */
    fun choose(option: LevelUpOption) {
        if (status != RunStatus.LEVEL_UP) return
        // A combat-equipment rank past the first grants a rolled turret upgrade alongside the rank.
        option.equipmentUpgrade?.let { player.equipmentUpgrades.add(it.contribution) }
        val existing = player.held.indexOfFirst { it.modifier.id == option.modifier.id }
        if (existing >= 0) {
            player.held[existing] = HeldModifier(option.modifier, option.resultingRank)
        } else {
            player.held.add(HeldModifier(option.modifier, option.resultingRank))
        }
        rebuildStats()
        levelUpOptions = emptyList()
        status = RunStatus.RUNNING
    }

    fun snapshot(): RunSnapshot = RunSnapshot(
        worldSize = arena.worldSize,
        activeMin = arena.min(),
        activeMax = arena.max(),
        cellSize = arena.cellSize,
        playerPos = player.pos,
        playerHits = player.hits.coerceAtLeast(0),
        playerMaxHits = player.maxHits,
        playerInvuln = player.invuln > 0f,
        playerAim = player.aim,
        playerMuzzleFrac = (player.muzzleFlash / MUZZLE_SECONDS).coerceIn(0f, 1f),
        playerHurtFrac = (player.hurtFlash / HURT_SECONDS).coerceIn(0f, 1f),
        level = player.level,
        levelCap = config.levelCap,
        xp = player.xp,
        xpToNext = player.xpToNext,
        gold = player.gold,
        ammo = player.ammo.coerceAtLeast(0),
        magazine = player.magazine(stats),
        reloading = player.reloadRemaining > 0f,
        reloadFrac = if (player.reloadRemaining > 0f && weapon.reloadSeconds > 0f)
            (1f - (player.reloadRemaining / (weapon.reloadSeconds / player.reloadSpeed(stats)))).coerceIn(0f, 1f)
        else 0f,
        spinUp = weapon.spinUpAccel > 0f,
        spinFrac = if (weapon.spinUpAccel > 0f) {
            val ceiling = player.fireRate(stats)
            ((effectiveFireRate() - weapon.spinUpFloor) / (ceiling - weapon.spinUpFloor).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        } else 0f,
        wave = wave + 1,
        totalWaves = config.waves,
        tier = tier,
        maxSets = config.maxSets,
        devRun = config.devRun,
        score = score,
        status = status,
        revives = revives,
        strained = resolver.strained,
        enemies = enemies.map {
            EnemyView(
                pos = it.pos,
                radius = it.type.radius,
                healthFrac = (it.health / it.maxHealth).coerceIn(0f, 1f),
                type = it.type,
                hitFlashFrac = (it.hitFlash / HIT_FLASH_SECONDS).coerceIn(0f, 1f),
                facing = (player.pos - it.pos).normalized(),
            )
        },
        projectiles = projectiles.filter { it.friendly }.map { it.pos },
        enemyProjectiles = projectiles.filter { !it.friendly }.map { it.pos },
        structures = structures.map {
            StructureView(
                it.pos, it.type, (it.hp / it.maxHp).coerceIn(0f, 1f), it.aim,
                artifactTurret = it.artifactTurret,
                ttlFrac = if (it.maxTtl != Float.POSITIVE_INFINITY) (it.ttl / it.maxTtl).coerceIn(0f, 1f) else 1f,
            )
        },
        structureCosts = StructureType.values().associateWith { buildCost(it) },
        mines = mines.map { MineView(it.pos, it.armed, stats.resolve(Stat.MINE_TRIGGER)) },
        pickups = pickups.map { PickupView(it.pos, it.kind) },
        effects = effects.map { EffectView(it.pos, it.kind, (it.age / it.ttl).coerceIn(0f, 1f), it.worldRadius) },
        boss = enemies.firstOrNull { it.kind == EntityKind.BOSS }
            ?.let { BossView((it.health / it.maxHealth).coerceIn(0f, 1f), it.type.displayName) },
        levelUpOptions = levelUpOptions,
        setBonusOptions = setBonusOptions,
        storeOptions = storeOptions,
        overflowOptions = overflowOptions,
        weapon = weapon,
        weaponName = weapon.displayName,
        challengeModeName = config.challengeMode.name,
        held = player.held.map { HeldView(it.modifier.name, it.rank, it.modifier.maxRank) },
        boons = player.runBonuses.mapNotNull { c -> SetBonuses.BOONS.firstOrNull { it.contribution == c }?.name },
        tempBuffs = player.tempBuffs.map { TempBuffView(it.label, it.remaining.coerceAtLeast(0f)) },
        expand = arena.nextCost()?.let { ExpandPrompt(it, player.gold >= it, arena.stage, arena.maxStage) },
    )

    companion object {
        const val PLAYER_ID = 0
        const val STORE_OFFER_COUNT = 4    // the between-set store shows four random offers (§9)
        const val EXPLOSION_DAMAGE_FRAC = 0.6f // splash damage as a fraction of the direct hit (§9)
        const val RICOCHET_LIFE = 1.2f     // seconds of travel a bounced shot is guaranteed for its new leg
        const val SPAWN_INTERVAL = 0.30f   // faster drip — the open arena wants a real swarm
        const val BREATHER_SECONDS = 2.5f
        const val BOSS_STAGGER = 1.2f      // seconds between multiple bosses entering
        const val HIT_FLASH_SECONDS = 0.09f
        const val BURST_SECONDS = 0.35f
        const val MUZZLE_SECONDS = 0.06f
        const val HURT_SECONDS = 0.16f
        const val INVULN_SECONDS = 0.8f    // i-frames after a hit — one contact = one heart
        const val REVIVE_INVULN = 2.0f     // long grace window on revive, to escape the pile (§7)
        const val REVIVE_CLEAR_RADIUS = 220f // enemies/fire cleared around the player on revive (§7)
        const val ATTACK_INTERVAL = 0.6f   // seconds between an enemy's blows on a structure
        const val TURRET_DEPLOY_INTERVAL = 1.5f // seconds between auto-turret deployments
        const val OUTPOST_DEPLOY_INTERVAL = 12f // seconds between Outpost emplacements — permanent, so slow
        const val MINE_DEPLOY_INTERVAL = 1.4f   // seconds between mine deployments (§9)
        const val MINE_ARM_TIME = 0.6f          // seconds before a sown mine can detonate
        const val MINE_MIN_DIST = 45f           // ring around the player a mine is sown within
        const val MINE_MAX_DIST = 120f
        const val TWO_PI = (2.0 * Math.PI).toFloat()
        const val DECOY_DEPLOY_INTERVAL = 2.0f  // seconds between (re)planting a decoy (§9)
        const val DECOY_BLAST_DAMAGE = 60f       // damage of a destroyed decoy's death blast, if upgraded
        // Sniper "never reaches end of range" (§4): a lifetime long enough to cross the max arena at
        // its projectile speed, so range never clips its shots — only a hit or the arena edge does.
        const val UNLIMITED_LIFE = 6f
        const val UNLIMITED_AIM_RANGE = 4000f
    }
}
