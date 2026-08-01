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
 */
class RunEngine(
    val config: RunConfig,
    val bus: EventBus = EventBus(),
    private val resolver: EffectResolver = EffectResolver(),
) {
    /** The open, expandable play space. */
    val arena = ArenaState()
    private val rng = RunSeed(config.seed)

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
    private val occupancy = HashMap<Int, Structure>()
    private val worldCols = (arena.worldSize.x / arena.cellSize).toInt().coerceAtLeast(1)

    private fun colOf(x: Float) = (x / arena.cellSize).toInt()
    private fun rowOf(y: Float) = (y / arena.cellSize).toInt()
    private fun cellKey(col: Int, row: Int) = row * worldCols + col
    private fun structureAt(pos: Vec2): Structure? = occupancy[cellKey(colOf(pos.x), rowOf(pos.y))]

    var status: RunStatus = RunStatus.RUNNING
        private set
    var score: Long = 0L
        private set
    var wave: Int = 0
        private set
    /** Endless-mode loop counter: every full waves+boss clear bumps it, scaling all enemies (§7). */
    var tier: Int = 0
        private set
    /** How many times the player has bought back into this run after a defeat (DESIGN.md §7). */
    var revives: Int = 0
        private set

    private var stats: StatBlock = player.buildStats()
    /** The auto-turret's effective stats: its base auto-weapon rows + the player's AUTO-scope build. */
    private var turretStats: StatBlock = buildTurretStats()
    private var nextId = 1
    /** Cooldown between auto-turret deployments, so the Turret artifact drips them in (not all at once). */
    private var turretDeployTimer = 0f
    /** Cooldown between mine deployments, so the Mines equipment sows them in over time. */
    private var mineDeployTimer = 0f
    /** Cooldown between decoy deployments, so the Decoy equipment replants them as they're torn down. */
    private var decoyDeployTimer = 0f
    /** Cooldown between Outpost deployments — permanent emplacements drip in slowly, one at a time. */
    private var outpostDeployTimer = 0f

    /** Hosts every enemy-modifying decision for this run (DESIGN.md §8): challenge-mode modifiers,
     *  remap tables, and per-enemy artifacts. Standard runs install [ChallengeMode.NONE]. */
    val director = Director(bus)

    // Wave director state.
    private var spawnedThisWave = 0
    private var toSpawnThisWave = 0
    private var spawnTimer = 0f
    private var breatherTimer = 0f
    private var bossTimer = 0f
    private var bossesToSpawn = 0
    private var bossesSpawned = 0
    private var pendingBosses: List<EnemyType> = emptyList()

    private var levelUpOptions: List<LevelUpOption> = emptyList()
    private var setBonusOptions: List<SetBonusOption> = emptyList()
    private var overflowOptions: List<OverflowOption> = emptyList()
    private var storeOptions: List<StoreOffer> = emptyList()

    /** Store items owned this run (DESIGN.md §9): the persisted unlocks, plus anything bought at a set
     *  boundary so far. Seeds the level-up draft pool and gates the store from re-offering owned items. */
    private val runUnlockedIds: MutableSet<String> = config.unlockedIds.toMutableSet()

    /** The live aimed weapon — a store gun can swap it mid-run, so read it, never [config].weapon. */
    private val weapon: StartingWeapon get() = player.weapon

    init {
        director.configure(config.challengeMode, stats)
        // Loadout-toggled mutators the player owns (DESIGN.md §9) apply from the first frame.
        config.activeMutatorIds.forEach { id ->
            (StoreCatalog.byId(id) as? StoreCatalog.Item.MutatorItem)?.let { applyMutator(it) }
        }
        beginWave(0)
    }

    private fun rebuildStats() {
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
    private fun buildTurretStats(): StatBlock {
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

    // --- Movement ---------------------------------------------------------------------------

    private fun movePlayer(dt: Float, input: RunInput) {
        val dir = input.move.clampLength(1f)
        if (dir.length() <= Vec2.EPSILON) return
        val speed = player.moveSpeed(stats)
        player.pos = arena.clamp(player.pos + dir * (speed * dt), player.radius)
    }

    private fun moveEnemies(dt: Float) {
        for (e in enemies) {
            // Most enemies beeline the player (Geometry Wars); rushers make for the nearest base, and
            // a decoy pulls any enemy that has strayed beyond its lure range. A targeted structure in
            // reach is smashed on the spot rather than orbited.
            val targetStructure = targetStructureFor(e)
            if (targetStructure != null &&
                e.pos.distanceTo(targetStructure.pos) <= e.type.radius + arena.cellSize * 0.6f) {
                attackStructure(targetStructure, e)
                continue
            }
            moveEnemyToward(e, targetStructure?.pos ?: player.pos, dt)
        }
    }

    /**
     * Slide [e] toward [target] one axis at a time. *Any* structure in the next step is an obstacle:
     * the enemy stops and smashes it rather than passing through (DESIGN.md §9) — turrets and decoys
     * are struck the same as walls. [StructureType.blocks] now only governs whether the cell also
     * stops enemy *fire*, not movement.
     */
    private fun moveEnemyToward(e: Enemy, target: Vec2, dt: Float) {
        val dir = (target - e.pos).normalized()
        val delta = dir * (e.moveSpeed * dt)
        var p = e.pos
        val here = structureAt(p)
        val tryX = Vec2(p.x + delta.x, p.y)
        val sX = structureAt(tryX)
        if (sX != null && sX !== here) attackStructure(sX, e) else p = tryX
        val tryY = Vec2(p.x, p.y + delta.y)
        val sY = structureAt(tryY)
        if (sY != null && sY !== here) attackStructure(sY, e) else p = Vec2(p.x, tryY.y)
        e.pos = arena.clamp(p, e.type.radius)
    }

    /**
     * Which structure [e] is hunting, or null to chase the player (DESIGN.md §9). Rushers prioritise
     * the nearest base — they're the base-breakers — so they'll tear down turrets/decoys before the
     * player. Everyone else is pulled to the nearest decoy only once they've strayed beyond its lure
     * range from the player, so close pressure still lands on you while the outer swarm peels off.
     */
    private fun targetStructureFor(e: Enemy): Structure? {
        if (structures.isEmpty()) return null
        if (e.type == EnemyType.RUSHER) return nearestStructure(e.pos) { true }
        val decoy = nearestStructure(e.pos) { it.type == StructureType.DECOY } ?: return null
        val lure = stats.resolve(Stat.DECOY_RANGE)
        return if (e.pos.distanceTo(player.pos) > lure) decoy else null
    }

    private inline fun nearestStructure(from: Vec2, predicate: (Structure) -> Boolean): Structure? {
        var best: Structure? = null
        var bestDist = Float.MAX_VALUE
        for (s in structures) {
            if (!s.alive || !predicate(s)) continue
            val d = from.distanceTo(s.pos)
            if (d < bestDist) { bestDist = d; best = s }
        }
        return best
    }

    private fun attackStructure(s: Structure, e: Enemy) {
        if (e.attackCooldown > 0f) return
        e.attackCooldown = ATTACK_INTERVAL
        s.hp -= e.type.contactHits.toFloat() // durability is in hits; a blow removes contactHits
        bus.emit(GameEvent.OnHit(e.id, s.id, e.type.contactHits.toFloat(), false))
        // A reinforced wall (Outpost's BARRICADE_THORNS upgrade) bites back at whatever strikes it —
        // flat damage on every blow, routed through the resolver like any other hit (DESIGN.md §9).
        if (s.type == StructureType.BARRICADE) {
            val thorns = stats.resolve(Stat.BARRICADE_THORNS)
            if (thorns > 0f) resolver.resolve {
                e.health -= thorns
                e.hitFlash = HIT_FLASH_SECONDS
                bus.emit(GameEvent.OnHit(PLAYER_ID, e.id, thorns, false))
                if (!e.alive) killEnemy(e)
            }
        }
    }

    /** Ranged enemies (Spitter) fire bursts at the player: a few quick shots, then a long recovery. */
    private fun updateEnemyFire(dt: Float) {
        for (e in enemies) {
            if (!e.type.isRanged) continue
            e.fireCooldown -= dt
            if (e.fireCooldown > 0f) continue
            if (e.pos.distanceTo(player.pos) > e.type.range) continue
            val dir = (player.pos - e.pos).normalized()
            projectiles.add(
                Projectile(
                    id = nextId++, ownerId = e.id, pos = e.pos, vel = dir * e.type.projectileSpeed,
                    damage = 1f, crit = false, lifeRemaining = e.type.range / e.type.projectileSpeed, friendly = false,
                )
            )
            e.burstShots++
            if (e.burstShots >= e.type.burstCount) {
                e.burstShots = 0
                e.fireCooldown = e.type.burstCooldown // long recovery between bursts
            } else {
                e.fireCooldown = 1f / e.type.fireRate // quick shots within a burst
            }
        }
    }

    /** Turrets auto-fire at the nearest enemy in range; expired/dead structures are cleared. */
    private fun updateStructures(dt: Float) {
        // Artifact auto-turrets read the boosted turret stats; static gold Sentries fire on their own
        // fixed type stats, independent of the player's build.
        val autoDamage = turretStats.resolve(Stat.DAMAGE, Scope.AUTO)
        val autoRange = turretStats.resolve(Stat.RANGE, Scope.AUTO)
        val autoFireRate = turretStats.resolve(Stat.FIRE_RATE, Scope.AUTO).coerceAtLeast(0.1f)
        val autoSpeed = turretStats.resolve(Stat.PROJECTILE_SPEED, Scope.AUTO)
        val autoProjectiles = turretStats.resolve(Stat.PROJECTILES, Scope.AUTO).toInt().coerceAtLeast(1)
        // Static Sentries fire on their own fixed type stats, lifted by the Outpost's line-wide
        // buffs (base 1.0 multipliers, so no Outpost owned = unchanged) — gold-built Sentries included.
        val sentryDamageMult = stats.resolve(Stat.SENTRY_DAMAGE)
        val sentryFireRateMult = stats.resolve(Stat.SENTRY_FIRE_RATE)
        val it = structures.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.ttl != Float.POSITIVE_INFINITY) s.ttl -= dt // auto-turrets age toward expiry
            if (!s.alive) {
                occupancy.remove(cellKey(s.col, s.row))
                // A destroyed decoy detonates if the death-blast upgrade is owned (DESIGN.md §9).
                if (s.type == StructureType.DECOY) {
                    val blast = stats.resolve(Stat.DECOY_BLAST_RADIUS)
                    if (blast > 0f) explode(s.pos, blast, DECOY_BLAST_DAMAGE, directTargetId = -1)
                }
                effects.add(RunEffect(s.pos, EffectKind.DEATH_BURST, worldRadius = arena.cellSize * 0.5f, ttl = BURST_SECONDS))
                it.remove()
                continue
            }
            if (!s.type.isTurret) continue
            val damage = if (s.artifactTurret) autoDamage else s.type.damage * sentryDamageMult
            val range = if (s.artifactTurret) autoRange else s.type.range
            val fireRate = if (s.artifactTurret) autoFireRate else (s.type.fireRate * sentryFireRateMult).coerceAtLeast(0.1f)
            val speed = if (s.artifactTurret) autoSpeed else s.type.projectileSpeed
            // Only artifact turrets gain extra projectiles (from a rolled upgrade); Sentries fire one.
            val proj = if (s.artifactTurret) autoProjectiles else 1
            s.fireCooldown -= dt
            var target: Enemy? = null
            var bestDist = range
            for (en in enemies) {
                val d = en.pos.distanceTo(s.pos)
                if (d <= bestDist) { bestDist = d; target = en }
            }
            val t = target ?: continue
            s.aim = (t.pos - s.pos).normalized()
            if (s.fireCooldown <= 0f) {
                s.fireCooldown = 1f / fireRate
                fireTurretVolley(s.pos, s.aim, damage, s.id, speed, range, proj)
            }
        }
    }

    /** Fire [count] friendly turret projectiles along [dir] with a small fan when there's more than one. */
    private fun fireTurretVolley(origin: Vec2, dir: Vec2, damage: Float, ownerId: Int, speed: Float, range: Float, count: Int) {
        val baseAngle = kotlin.math.atan2(dir.y, dir.x)
        val spread = if (count > 1) 0.30f else 0f
        for (i in 0 until count) {
            val fanT = if (count == 1) 0f else (i / (count - 1f)) - 0.5f
            val angle = baseAngle + fanT * spread
            val vel = Vec2(cos(angle), sin(angle)) * speed
            val p = Projectile(
                id = nextId++, ownerId = ownerId, pos = origin, vel = vel,
                damage = damage, crit = false, lifeRemaining = range / speed, friendly = true,
            )
            projectiles.add(p)
            bus.emit(GameEvent.OnProjectileSpawn(p.id, ownerId))
        }
    }

    /**
     * Keep the number of auto-turrets the Turret artifact grants deployed near the player (DESIGN.md
     * §4). Turrets drip in on a cooldown and expire on their TTL, so at rank N you cycle N turrets.
     */
    private fun updateArtifactTurrets(dt: Float) {
        val desired = stats.resolve(Stat.TURRET_COUNT, Scope.AUTO).toInt()
        if (desired <= 0) return
        if (turretDeployTimer > 0f) turretDeployTimer -= dt
        val current = structures.count { it.artifactTurret }
        if (current < desired && turretDeployTimer <= 0f) {
            if (deployArtifactTurret()) turretDeployTimer = TURRET_DEPLOY_INTERVAL
        }
    }

    /** Deploy one auto-turret at the nearest free grid cell to the player. Returns whether it placed. */
    private fun deployArtifactTurret(): Boolean {
        val cell = freeCellNearPlayer() ?: return false
        val (col, row) = cell
        val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
        val ttl = turretStats.resolve(Stat.TURRET_TTL, Scope.AUTO).coerceAtLeast(2f)
        val s = Structure(
            id = nextId++, type = StructureType.TURRET, col = col, row = row, pos = center,
            hp = StructureType.TURRET.maxHp, maxHp = StructureType.TURRET.maxHp,
            artifactTurret = true, ttl = ttl, maxTtl = ttl,
        )
        structures.add(s)
        occupancy[cellKey(col, row)] = s
        bus.emit(GameEvent.OnSpawn(s.id, EntityKind.TURRET))
        return true
    }

    /**
     * The Mines equipment (DESIGN.md §9): keep [Stat.MINE_COUNT] proximity mines sown near the player,
     * dripping them in on a cooldown; detonate any armed mine an enemy wanders onto for an area blast.
     * Blast damage / radius / trigger are read from the live build, so rolled mine upgrades apply.
     */
    private fun updateMines(dt: Float) {
        // Age arming timers, then detonate any armed mine with an enemy in trigger range.
        val trigger = stats.resolve(Stat.MINE_TRIGGER)
        val radius = stats.resolve(Stat.MINE_RADIUS)
        val damage = stats.resolve(Stat.MINE_DAMAGE)
        val it = mines.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m.arming > 0f) { m.arming -= dt; continue }
            val triggered = enemies.any { e -> e.alive && e.pos.distanceTo(m.pos) <= trigger + e.type.radius }
            if (triggered) {
                explode(m.pos, radius, damage, directTargetId = -1) // -1: no direct target, whole blast splashes
                it.remove()
            }
        }
        // Keep MINE_COUNT mines sown, dripping them in near the player on a cooldown.
        val desired = stats.resolve(Stat.MINE_COUNT).toInt()
        if (mineDeployTimer > 0f) mineDeployTimer -= dt
        if (desired > 0 && mines.size < desired && mineDeployTimer <= 0f) {
            deployMine()
            mineDeployTimer = MINE_DEPLOY_INTERVAL
        }
    }

    /** Sow one mine at a random in-arena point ringing the player. */
    private fun deployMine() {
        val ang = rng.nextFloat(0f, TWO_PI)
        val dist = rng.nextFloat(MINE_MIN_DIST, MINE_MAX_DIST)
        val pos = arena.clamp(Vec2(player.pos.x + cos(ang) * dist, player.pos.y + sin(ang) * dist), 6f)
        mines.add(Mine(id = nextId++, pos = pos, arming = MINE_ARM_TIME))
    }

    /**
     * The Decoy equipment (DESIGN.md §9): keep [Stat.DECOY_COUNT] decoys planted near the player,
     * replanting on a cooldown as enemies tear them down. Each is a non-blocking structure deployed
     * with build-scaled HP; the targeting layer routes distant aggro onto it.
     */
    private fun updateDecoys(dt: Float) {
        val desired = stats.resolve(Stat.DECOY_COUNT).toInt()
        if (desired <= 0) return
        if (decoyDeployTimer > 0f) decoyDeployTimer -= dt
        val current = structures.count { it.type == StructureType.DECOY }
        if (current < desired && decoyDeployTimer <= 0f) {
            if (deployDecoy()) decoyDeployTimer = DECOY_DEPLOY_INTERVAL
        }
    }

    /** Plant one decoy at the nearest free grid cell to the player, with build-scaled durability. */
    private fun deployDecoy(): Boolean {
        val cell = freeCellNearPlayer() ?: return false
        val (col, row) = cell
        val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
        val hp = stats.resolve(Stat.DECOY_HP).coerceAtLeast(1f)
        val s = Structure(
            id = nextId++, type = StructureType.DECOY, col = col, row = row, pos = center,
            hp = hp, maxHp = hp,
        )
        structures.add(s)
        occupancy[cellKey(col, row)] = s
        return true
    }

    /**
     * The Outpost equipment (DESIGN.md §9): keep [Stat.OUTPOST_COUNT] permanent emplacements planted
     * near the player — each a static Sentry with a Barricade walled in behind it (barricade | sentry
     * | player). Unlike the auto-turret these never expire, so an Outpost seeds lasting strongpoints
     * as you roam; they drip in one at a time on a slow cooldown. Only the engine's own emplacements
     * count toward the target, so gold-built Sentries never suppress deployment.
     */
    private fun updateOutpost(dt: Float) {
        val desired = stats.resolve(Stat.OUTPOST_COUNT).toInt()
        if (desired <= 0) return
        if (outpostDeployTimer > 0f) outpostDeployTimer -= dt
        // One Sentry anchors each emplacement; the paired Barricade rides along and isn't tallied.
        val current = structures.count { it.fromOutpost && it.type == StructureType.SENTRY }
        if (current < desired && outpostDeployTimer <= 0f) {
            if (deployOutpost()) outpostDeployTimer = OUTPOST_DEPLOY_INTERVAL
        }
    }

    /**
     * Plant one permanent Outpost: a Sentry at the nearest free cell to the player, and a Barricade one
     * cell beyond it — continuing the line away from the player, so it walls the emplacement's outer
     * face (barricade | sentry | player). If that cell is taken the Sentry still stands alone. Returns
     * whether the Sentry placed.
     */
    private fun deployOutpost(): Boolean {
        val (scol, srow) = freeCellNearPlayer() ?: return false
        plantOutpostStructure(StructureType.SENTRY, scol, srow)
        // Step one more cell along the player→sentry direction for the Barricade's cell.
        val bcol = scol + (scol - colOf(player.pos.x)).coerceIn(-1, 1)
        val brow = srow + (srow - rowOf(player.pos.y)).coerceIn(-1, 1)
        if ((bcol != scol || brow != srow) && isFreeCell(bcol, brow)) {
            plantOutpostStructure(StructureType.BARRICADE, bcol, brow)
        }
        return true
    }

    /** Add a permanent, engine-owned Outpost structure of [type] to the grid (no gold cost). */
    private fun plantOutpostStructure(type: StructureType, col: Int, row: Int) {
        val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
        val s = Structure(
            id = nextId++, type = type, col = col, row = row, pos = center,
            hp = type.maxHp, maxHp = type.maxHp, fromOutpost = true,
        )
        structures.add(s)
        occupancy[cellKey(col, row)] = s
    }

    /** Whether [col],[row] is an in-arena grid cell not already occupied by a structure. */
    private fun isFreeCell(col: Int, row: Int): Boolean {
        if (occupancy.containsKey(cellKey(col, row))) return false
        val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
        return arena.contains(center)
    }

    /** Search grid cells outward from the player's cell for the first free, in-arena, non-player cell. */
    private fun freeCellNearPlayer(): Pair<Int, Int>? {
        val pcol = colOf(player.pos.x)
        val prow = rowOf(player.pos.y)
        for (ring in 1..4) {
            for (dc in -ring..ring) for (dr in -ring..ring) {
                if (kotlin.math.max(kotlin.math.abs(dc), kotlin.math.abs(dr)) != ring) continue // ring edge only
                val col = pcol + dc
                val row = prow + dr
                if (col == pcol && row == prow) continue
                if (occupancy.containsKey(cellKey(col, row))) continue
                val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
                if (!arena.contains(center)) continue
                return col to row
            }
        }
        return null
    }

    // --- Waves ------------------------------------------------------------------------------

    private fun beginWave(index: Int) {
        wave = index
        spawnedThisWave = 0
        bossesSpawned = 0
        // Final wave spawns the cumulative boss roster for the current tier — every boss unlocked so
        // far shows up together (Abomination, then + Spitter Boss, then + Rusher Swarm, …).
        pendingBosses = if (isFinalWave()) bossRosterFor(tier) else emptyList()
        bossesToSpawn = pendingBosses.size
        spawnTimer = 0f
        bossTimer = 0f
        // Director spawn multiplier (Mirror) and the endless tier both scale the wave.
        val baseBudget = if (isFinalWave()) 10 + index * 2 else 14 + index * 4
        toSpawnThisWave = (baseBudget * director.spawnMult() * Tiers.spawnMult(tier)).roundToInt().coerceAtLeast(1)
        bus.emit(GameEvent.OnWaveStart(index + 1))
    }

    private fun isFinalWave(): Boolean = wave >= config.waves - 1

    private fun updateWaves(dt: Float) {
        if (breatherTimer > 0f) {
            breatherTimer -= dt
            return
        }
        // Spawn drip.
        if (spawnedThisWave < toSpawnThisWave) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                spawnWaveEnemy()
                spawnedThisWave++
                spawnTimer = SPAWN_INTERVAL
            }
        }
        // Bosses roll in once the final wave's trash is half out, dripping so they don't stack at once.
        if (isFinalWave() && bossesSpawned < bossesToSpawn && spawnedThisWave >= toSpawnThisWave / 2) {
            bossTimer -= dt
            if (bossTimer <= 0f) {
                spawnEnemy(pendingBosses[bossesSpawned], hpScale = 1f)
                bossesSpawned++
                bossTimer = BOSS_STAGGER
            }
        }
        // Wave clears when everything spawned is dead.
        val bossesDone = !isFinalWave() || bossesSpawned >= bossesToSpawn
        val doneSpawning = spawnedThisWave >= toSpawnThisWave && bossesDone
        if (doneSpawning && enemies.isEmpty()) {
            score += 100L * (wave + 1)
            if (isFinalWave()) {
                // Endless: the boss is down, so loop back to wave 1 at a higher tier/"set" — the
                // enemies return "leveled up" (DESIGN.md §7). The run only ever ends on death.
                tier++
                score += 500L * tier
                // Bounded runs (the weekly dev run) finish here instead of looping — once the set
                // ceiling is cleared the run is a win, not another tier. Endless runs (maxSets null)
                // fall through and keep escalating.
                config.maxSets?.let { cap ->
                    if (tier >= cap) { endRun(victory = true); return }
                }
                // The set draft (DESIGN.md §7): pause for a boon/bane pick, then begin the next set
                // when it's chosen. No offers (should not happen) → just roll straight on.
                setBonusOptions = rollSetBonusOptions()
                if (setBonusOptions.isNotEmpty()) {
                    status = RunStatus.SET_BONUS
                } else {
                    breatherTimer = BREATHER_SECONDS * 1.5f
                    beginWave(0)
                }
            } else {
                breatherTimer = BREATHER_SECONDS
                beginWave(wave + 1)
            }
        }
    }

    private fun spawnWaveEnemy() {
        // Weighted pick among the trash archetypes unlocked at the current tier (each tier reveals a
        // new one). Bosses are never in this pool — they come from the final-wave roster.
        val pool = EnemyType.values().filter { !it.isBoss && it.unlockTier <= tier }
        val totalWeight = pool.sumOf { it.spawnWeight }
        var r = rng.nextInt(totalWeight.coerceAtLeast(1))
        var chosen = pool.first()
        for (t in pool) {
            r -= t.spawnWeight
            if (r < 0) { chosen = t; break }
        }
        spawnEnemy(chosen, hpScale = 1f)
    }

    /**
     * The cumulative boss roster for [tier]: every boss whose unlockTier is reached, each repeated
     * [EnemyType.bossCount] times, ordered by unlock. Past the last defined boss, extra Abominations
     * keep the finale escalating.
     */
    private fun bossRosterFor(tier: Int): List<EnemyType> {
        val bosses = EnemyType.values().filter { it.isBoss }
        val roster = ArrayList<EnemyType>()
        bosses.filter { it.unlockTier <= tier }.sortedBy { it.unlockTier }.forEach { b ->
            repeat(b.bossCount) { roster.add(b) }
        }
        val maxBossTier = bosses.maxOf { it.unlockTier }
        if (tier > maxBossTier) repeat(tier - maxBossTier) { roster.add(EnemyType.ABOMINATION) }
        return roster
    }


    private fun spawnEnemy(type: EnemyType, hpScale: Float) {
        val pos = spawnPoint()
        // Build the enemy through the same StatBlock path the player uses: its archetype base, then
        // the Director's per-enemy multipliers, then any enemy-attached artifacts (Mob Boss). An
        // aimed-scope artifact is simply inert on an enemy with no weapon (DESIGN.md §3).
        val block = StatBlock(
            mapOf(
                Stat.MAX_HEALTH to type.maxHealth,
                Stat.MOVE_SPEED to type.moveSpeed,
            )
        )
        // Director multipliers (challenge modes) and the endless tier both compound onto the base.
        block.add(StatContribution(Stat.MAX_HEALTH, Scope.GLOBAL, Op.MULTIPLY, director.enemyHpMult() * Tiers.hpMult(tier)))
        block.add(StatContribution(Stat.MOVE_SPEED, Scope.GLOBAL, Op.MULTIPLY, director.enemySpeedMult() * Tiers.speedMult(tier)))
        director.enemyModifiers.forEach { block.addAll(it.contributions()) }

        val hp = block.resolve(Stat.MAX_HEALTH, Scope.GLOBAL) * hpScale
        val e = Enemy(
            id = nextId++,
            type = type,
            pos = pos,
            health = hp,
            maxHealth = hp,
            moveSpeed = block.resolve(Stat.MOVE_SPEED, Scope.GLOBAL),
            held = director.enemyModifiers,
        )
        enemies.add(e)
        bus.emit(GameEvent.OnSpawn(e.id, e.kind))
    }

    /** Enemies enter at a random point on the current active arena edge. */
    private fun spawnPoint(): Vec2 = arena.randomEdge(rng)

    // --- Weapon fire ------------------------------------------------------------------------

    private fun fireWeapon(dt: Float, input: RunInput) {
        // Reload gate (DESIGN.md §4): while reloading, the weapon is offline. When it finishes, the
        // magazine is refilled. Reload runs whether or not there is a target to shoot at, and spins
        // the Gatling back down.
        if (player.reloadRemaining > 0f) {
            player.reloadRemaining -= dt
            if (player.reloadRemaining <= 0f) {
                player.reloadRemaining = 0f
                player.ammo = player.magazine(stats)
            }
            player.spin = 0f
            return
        }

        player.fireCooldown -= dt

        // Empty magazine → auto-reload (no target needed to start the reload); spin resets.
        if (player.ammo <= 0) { player.spin = 0f; beginReload(); return }

        val aimDir = resolveAim(input)
        if (aimDir == null) { player.spin = 0f; return } // disengaged → spin down ("reset on restart")

        // Engaged this frame: wind the spin-up up (Gatling), then gate on the resulting cadence.
        player.spin += dt
        if (player.fireCooldown > 0f) return
        player.fireCooldown = 1f / effectiveFireRate()
        player.ammo -= 1 // one trigger-pull spends one round, however many projectiles it throws

        val count = player.projectiles(stats)
        var perHit = player.aimedDamage(stats)
        // DPS-normalized weapons (none at present, DESIGN.md §4) split throughput across projectiles:
        // per-hit damage is divided by the count so more projectiles buy coverage, not raw DPS.
        if (weapon.dpsNormalized && count > 1) perHit /= count.toFloat()

        val baseAngle = kotlin.math.atan2(aimDir.y, aimDir.x)
        val spread = if (count > 1) weapon.spread else 0f // per-weapon fan (Shotgun spreads wide)
        val speed = player.projectileSpeed(stats)
        // Sniper never falls short (DESIGN.md §4): its shots live long enough to cross the arena and
        // only die on a hit or at the edge. The others expire at their range.
        val life = if (weapon.unlimitedRange) UNLIMITED_LIFE else player.range(stats) / speed
        val critChance = player.critChance(stats)
        val critMult = player.critMult(stats)
        // On-hit passives (DESIGN.md §9): resolved once from the aimed build and stamped onto every
        // pellet, so pierce/ricochet/explosive transform whatever gun is equipped.
        val pierce = stats.resolve(Stat.PIERCE, Scope.AIMED).toInt().coerceAtLeast(0)
        val bounces = stats.resolve(Stat.RICOCHET, Scope.AIMED).toInt().coerceAtLeast(0)
        val boom = stats.resolve(Stat.EXPLOSION_RADIUS, Scope.AIMED).coerceAtLeast(0f)

        for (i in 0 until count) {
            val t = if (count == 1) 0f else (i / (count - 1f)) - 0.5f
            val angle = baseAngle + t * spread
            val vel = Vec2(cos(angle), sin(angle)) * speed
            val crit = rng.chance(critChance)
            val dmg = if (crit) perHit * critMult else perHit
            val p = Projectile(
                id = nextId++, ownerId = PLAYER_ID, pos = player.pos, vel = vel,
                damage = dmg, crit = crit, lifeRemaining = life, friendly = true,
                pierceLeft = pierce, bouncesLeft = bounces, explosionRadius = boom,
            )
            projectiles.add(p)
            bus.emit(GameEvent.OnProjectileSpawn(p.id, PLAYER_ID))
        }
        player.muzzleFlash = MUZZLE_SECONDS
        if (player.ammo <= 0) beginReload() // that was the last round — start the auto-reload now
    }

    /**
     * Begin a reload if one isn't already running and the magazine isn't full (DESIGN.md §4). The
     * time taken is the weapon's base reload shortened by the Autoloader's RELOAD_SPEED multiplier.
     */
    private fun beginReload() {
        if (player.reloadRemaining > 0f || player.ammo >= player.magazine(stats)) return
        player.reloadRemaining = weapon.reloadSeconds / player.reloadSpeed(stats)
    }

    /** Reload on demand (DESIGN.md §4). Ignored while paused/over, mid-reload, or on a full magazine. */
    fun reload() {
        if (status != RunStatus.RUNNING) return
        beginReload()
    }

    /**
     * The live fire rate. Flat for most weapons; for a spin-up weapon (Gatling) it ramps from
     * [StartingWeapon.spinUpFloor] as [Player.spin] grows, with **no ceiling** — it keeps climbing
     * for as long as fire is sustained and is bounded only by the magazine emptying (a reload resets
     * the spin), DESIGN.md §4. Fire-rate passives scale the whole curve rather than raising a cap:
     * the ramp is expressed relative to the weapon's base fire rate and multiplied by the resolved
     * [Stat.FIRE_RATE], so Rapid Fire / Gunslinger / Berserker still speed the Gatling up.
     */
    private fun effectiveFireRate(): Float {
        val rate = player.fireRate(stats)
        if (weapon.spinUpAccel <= 0f) return rate
        val baseRate = (weapon.baseStats[Stat.FIRE_RATE] ?: rate).coerceAtLeast(0.01f)
        val ramp = (weapon.spinUpFloor + weapon.spinUpAccel * player.spin).coerceAtLeast(0.1f)
        return ramp * (rate / baseRate)
    }

    /** Track the aim direction every frame so the barrel/reticle follows the nearest target even
     *  between shots. Keeps the previous aim when there is no target, so the barrel never snaps. */
    private fun updateAim(input: RunInput) {
        resolveAim(input)?.let { player.aim = it }
    }

    /** Manual aim wins if given; otherwise auto-aim the nearest enemy within range (DESIGN.md §4). */
    private fun resolveAim(input: RunInput): Vec2? {
        input.aimOverride?.let { if (it.length() > Vec2.EPSILON) return it.normalized() }
        // The Sniper locks on anywhere (unlimited range, §4); the others only auto-aim within reach.
        val range = if (weapon.unlimitedRange) UNLIMITED_AIM_RANGE else player.range(stats)
        var best: Enemy? = null
        var bestDist = Float.MAX_VALUE
        for (e in enemies) {
            val d = e.pos.distanceTo(player.pos)
            if (d <= range && d < bestDist) { best = e; bestDist = d }
        }
        return best?.let { (it.pos - player.pos).normalized() }
    }

    private fun moveProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.pos = p.pos + p.vel * dt
            p.lifeRemaining -= dt
            if (p.lifeRemaining <= 0f || outOfArena(p.pos)) it.remove()
        }
    }

    private fun outOfArena(p: Vec2): Boolean =
        p.x < -16f || p.y < -16f || p.x > arena.worldSize.x + 16f || p.y > arena.worldSize.y + 16f

    /**
     * Enemy shots are stopped dead by a blocking structure's cell (barricade / static Sentry) — a wall
     * actually walls off incoming fire, so a Spitter can't shoot through it (DESIGN.md §7). Friendly
     * shots pass over your own defenses so a Sentry never blocks its own (or the player's) fire.
     */
    private fun resolveEnemyProjectileBlocks() {
        if (structures.isEmpty()) return
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.friendly) continue
            val s = structureAt(p.pos) ?: continue
            if (s.type.blocks) it.remove()
        }
    }

    // --- Collisions -------------------------------------------------------------------------

    private fun resolveProjectileHits() {
        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (!p.friendly) continue // only player/turret shots damage enemies
            // The first enemy in range this shot hasn't already struck — pierce/ricochet keep a shot
            // alive across frames, so a shot must never re-hit a body it already passed through.
            var target: Enemy? = null
            for (e in enemies) {
                if (!e.alive || e.id in p.hitIds) continue // dead-but-not-yet-culled bodies aren't targets
                if (p.pos.distanceTo(e.pos) <= e.type.radius + p.radius) { target = e; break }
            }
            val t = target ?: continue
            // Every hit is an event routed through the resolver's frame budget (invariants #2/#3).
            resolver.resolve {
                t.health -= p.damage
                t.hitFlash = HIT_FLASH_SECONDS
                p.hitIds.add(t.id)
                bus.emit(GameEvent.OnHit(PLAYER_ID, t.id, p.damage, p.crit))
                if (!t.alive) killEnemy(t)
                if (p.explosionRadius > 0f) explode(p.pos, p.explosionRadius, p.damage * EXPLOSION_DAMAGE_FRAC, t.id)
            }
            // Survive the hit via pierce first (straight through), then ricochet (bounce to a new
            // target); a shot with neither budget is spent on impact.
            when {
                p.pierceLeft > 0 -> p.pierceLeft--
                p.bouncesLeft > 0 -> {
                    p.bouncesLeft--
                    if (!redirectToNearest(p)) pit.remove()
                }
                else -> pit.remove()
            }
        }
        enemies.removeAll { !it.alive }
    }

    /**
     * An explosive shot's blast (DESIGN.md §9): splash [damage] to every enemy within [radius] of
     * [center], skipping the projectile's direct target (already damaged this hit). Each splash hit
     * goes through the resolver so a chain of explosions still respects the frame budget.
     */
    private fun explode(center: Vec2, radius: Float, damage: Float, directTargetId: Int) {
        effects.add(RunEffect(pos = center, kind = EffectKind.EXPLOSION, worldRadius = radius, ttl = BURST_SECONDS))
        for (e in enemies) {
            if (!e.alive || e.id == directTargetId) continue
            if (center.distanceTo(e.pos) > radius + e.type.radius) continue
            resolver.resolve {
                e.health -= damage
                e.hitFlash = HIT_FLASH_SECONDS
                bus.emit(GameEvent.OnHit(PLAYER_ID, e.id, damage, false))
                if (!e.alive) killEnemy(e)
            }
        }
    }

    /** Point [p] at the nearest enemy it hasn't hit yet (a ricochet). Returns false if none remain. */
    private fun redirectToNearest(p: Projectile): Boolean {
        var best: Enemy? = null
        var bestDist = Float.MAX_VALUE
        for (e in enemies) {
            if (!e.alive || e.id in p.hitIds) continue
            val d = e.pos.distanceTo(p.pos)
            if (d < bestDist) { bestDist = d; best = e }
        }
        val target = best ?: return false
        val speed = p.vel.length()
        p.vel = (target.pos - p.pos).normalized() * speed
        // A fresh leg of travel, so a bounced shot doesn't die to the previous target's range clock.
        p.lifeRemaining = maxOf(p.lifeRemaining, RICOCHET_LIFE)
        return true
    }

    private fun killEnemy(e: Enemy) {
        score += e.type.xpValue * 10L
        bus.emit(GameEvent.OnKill(PLAYER_ID, e.id, e.kind))
        if (e.kind == EntityKind.BOSS) {
            // Bosses always pay out — XP, gold, and a heart.
            pickups.add(Pickup(nextId++, e.pos, PickupKind.XP, e.type.xpValue))
            pickups.add(Pickup(nextId++, e.pos, PickupKind.GOLD, e.type.goldValue))
            pickups.add(Pickup(nextId++, e.pos, PickupKind.HEALTH, 1))
        } else {
            // Per-type drop chances (§7): trash stays stingy, elites pay out more. Independent rolls.
            if (rng.chance(e.type.xpDropChance)) pickups.add(Pickup(nextId++, e.pos, PickupKind.XP, e.type.xpValue))
            if (e.type.goldValue > 0 && rng.chance(e.type.goldDropChance)) pickups.add(Pickup(nextId++, e.pos, PickupKind.GOLD, e.type.goldValue))
        }
        effects.add(RunEffect(pos = e.pos, kind = EffectKind.DEATH_BURST, worldRadius = e.type.radius, ttl = BURST_SECONDS))
    }

    /** Age hit-flash / i-frame / attack timers and transient effects; drop expired. Visual + gameplay timers. */
    private fun ageVisuals(dt: Float) {
        for (e in enemies) {
            if (e.hitFlash > 0f) e.hitFlash = (e.hitFlash - dt).coerceAtLeast(0f)
            if (e.attackCooldown > 0f) e.attackCooldown -= dt
        }
        if (player.invuln > 0f) player.invuln -= dt
        if (player.muzzleFlash > 0f) player.muzzleFlash = (player.muzzleFlash - dt).coerceAtLeast(0f)
        if (player.hurtFlash > 0f) player.hurtFlash = (player.hurtFlash - dt).coerceAtLeast(0f)
        // Age temporary surges; when one lapses, rebuild stats so it cleanly falls off the block.
        if (player.tempBuffs.isNotEmpty()) {
            var expired = false
            val tb = player.tempBuffs.iterator()
            while (tb.hasNext()) {
                val b = tb.next()
                b.remaining -= dt
                if (b.remaining <= 0f) { tb.remove(); expired = true }
            }
            if (expired) rebuildStats()
        }
        val it = effects.iterator()
        while (it.hasNext()) {
            val fx = it.next()
            fx.age += dt
            if (fx.age >= fx.ttl) it.remove()
        }
    }

    /**
     * Contact damage in hearts. Enemy touch and incoming enemy projectiles each cost the player
     * hearts (a boss touch costs [EnemyType.contactHits] = 3), gated by invulnerability frames so a
     * single overlap costs one hit, not a heart per frame.
     */
    private fun resolveContact(dt: Float) {
        if (player.invuln > 0f) {
            // Still take the projectile off the field even while invulnerable, but no heart cost.
            projectiles.removeAll { !it.friendly && it.pos.distanceTo(player.pos) <= player.radius + it.radius }
            return
        }
        // Enemy body contact.
        for (e in enemies) {
            if (e.pos.distanceTo(player.pos) <= e.type.radius + player.radius) {
                hurtPlayer(e.type.contactHits)
                return
            }
        }
        // Enemy projectile hit (each costs one heart).
        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (p.friendly) continue
            if (p.pos.distanceTo(player.pos) <= player.radius + p.radius) {
                pit.remove()
                hurtPlayer(1)
                return
            }
        }
    }

    private fun hurtPlayer(hearts: Int) {
        player.hits -= hearts
        player.invuln = INVULN_SECONDS
        player.hurtFlash = HURT_SECONDS
        bus.emit(GameEvent.OnHit(-1, PLAYER_ID, hearts.toFloat(), false))
    }

    // --- Pickups & leveling -----------------------------------------------------------------

    private fun updatePickups(dt: Float) {
        // A set-bonus draft can be entered earlier this same frame (updateWaves). Don't collect XP
        // into a level-up/overflow that would clobber that pause and skip beginning the next set —
        // the pickups wait on the field until the run resumes.
        if (status != RunStatus.RUNNING) return
        val radius = player.pickupRadius(stats)
        val it = pickups.iterator()
        while (it.hasNext()) {
            val pk = it.next()
            val dist = pk.pos.distanceTo(player.pos)
            if (dist <= radius) {
                // Magnetize toward the player, then collect on contact.
                val pull = (player.pos - pk.pos).normalized() * (260f * dt)
                pk.pos = pk.pos + pull
            }
            if (pk.pos.distanceTo(player.pos) <= player.radius + pk.radius) {
                collect(pk)
                it.remove()
            }
        }
    }

    private fun collect(pk: Pickup) {
        when (pk.kind) {
            PickupKind.GOLD -> player.gold += pk.amount
            PickupKind.HEALTH -> {
                player.hits = (player.hits + pk.amount).coerceAtMost(player.maxHits)
                bus.emit(GameEvent.OnHeal(PLAYER_ID, pk.amount.toFloat()))
            }
            PickupKind.XP -> gainXp(pk.amount.toFloat())
        }
        bus.emit(GameEvent.OnPickup(PLAYER_ID, pk.kind))
    }

    private fun gainXp(amount: Float) {
        val gain = amount * stats.resolve(com.lifeops.app.game.core.Stat.XP_GAIN)
        player.xp += gain
        // Fill may cross the threshold multiple times in one collection; loop, but cap iterations
        // so a pathological XP_GAIN can't spin forever (degrade, don't hang — invariant #3).
        var guard = 0
        while (player.xp >= player.xpToNext && guard < 32) {
            guard++
            player.xp -= player.xpToNext
            if (player.level < config.levelCap) {
                levelUp()
                // A real level-up pauses for a pick; a fully-maxed build falls through to an
                // overflow pick instead. Either pause stops draining XP until the player resumes.
                if (status == RunStatus.LEVEL_UP || status == RunStatus.OVERFLOW) break
            } else {
                overflowLevel()
                // The overflow micro-pick also pauses; resume drains any leftover XP next tick.
                if (status == RunStatus.OVERFLOW) break
            }
        }
    }

    private fun levelUp() {
        player.level++
        player.xpToNext *= 1.28f
        bus.emit(GameEvent.OnLevelUp(player.level, overflow = false))
        val options = rollLevelUpOptions()
        if (options.isEmpty()) {
            // Everything maxed: no dead pause — treat as an overflow reward instead.
            overflowLevel()
        } else {
            levelUpOptions = options
            status = RunStatus.LEVEL_UP
        }
    }

    /**
     * At cap the XP bar keeps filling; each fill offers an instant micro-pick (DESIGN.md §6) — bonus
     * gold, healing, or a short temporary stat boost. The run pauses on the pick; [chooseOverflow]
     * applies it and resumes.
     */
    private fun overflowLevel() {
        player.xpToNext *= 1.15f
        bus.emit(GameEvent.OnLevelUp(player.level, overflow = true))
        score += 50L
        overflowOptions = rollOverflowOptions()
        status = RunStatus.OVERFLOW
    }

    /** Roll the three overflow offers: bonus gold, a heal, and one random temporary surge. */
    private fun rollOverflowOptions(): List<OverflowOption> {
        val surge = TempBoosts.SURGES[rng.nextInt(TempBoosts.SURGES.size)]
        return listOf(
            OverflowOption(OverflowKind.GOLD, "Bonus Gold", "+8 gold for this run", amount = 8),
            OverflowOption(OverflowKind.HEAL, "Field Medic", "Restore 1 heart", amount = 1),
            OverflowOption(
                OverflowKind.TEMP_BOOST, surge.label,
                "Lasts ${TempBoosts.DURATION_SECONDS.toInt()}s", surge = surge,
            ),
        )
    }

    /** Apply the chosen overflow micro-pick and resume (DESIGN.md §6). No-op unless paused on it. */
    fun chooseOverflow(option: OverflowOption) {
        if (status != RunStatus.OVERFLOW) return
        when (option.kind) {
            OverflowKind.GOLD -> player.gold += option.amount // dies with the run (invariant #1)
            OverflowKind.HEAL -> {
                val before = player.hits
                player.hits = (player.hits + option.amount).coerceAtMost(player.maxHits)
                if (player.hits > before) bus.emit(GameEvent.OnHeal(PLAYER_ID, (player.hits - before).toFloat()))
            }
            OverflowKind.TEMP_BOOST -> option.surge?.let {
                player.tempBuffs.add(TempBuff(it.contribution, it.label, TempBoosts.DURATION_SECONDS))
                rebuildStats()
            }
        }
        score += 40L
        overflowOptions = emptyList()
        status = RunStatus.RUNNING
    }

    /** Roll the per-set draft: three distinct boons, each paired with a random enemy bane (§7). */
    private fun rollSetBonusOptions(): List<SetBonusOption> {
        val boonPool = SetBonuses.BOONS.toMutableList()
        val out = ArrayList<SetBonusOption>(3)
        while (boonPool.isNotEmpty() && out.size < 3) {
            val boon = boonPool.removeAt(rng.nextInt(boonPool.size))
            val bane = SetBonuses.BANES[rng.nextInt(SetBonuses.BANES.size)]
            out.add(SetBonusOption(boon, bane))
        }
        return out
    }

    /**
     * Apply the chosen set draft and begin the next set (DESIGN.md §7). The boon is a permanent
     * player run-bonus; the bane is layered onto the Director so every enemy spawned in the new set
     * is tougher. No-op unless paused on the set draft.
     */
    fun chooseSetBonus(option: SetBonusOption) {
        if (status != RunStatus.SET_BONUS) return
        player.runBonuses.add(option.boon.contribution)
        director.addRunBane(option.bane.contribution)
        rebuildStats()
        setBonusOptions = emptyList()
        // The boon/bane draft is followed by the between-set store (DESIGN.md §9), then the next set.
        enterStoreOrResume()
    }

    // --- Between-set store (DESIGN.md §9) ---------------------------------------------------------

    /**
     * After the set draft, open the store on up to [STORE_OFFER_COUNT] random unowned offers; if the
     * catalog is exhausted (everything owned) there is nothing to show, so roll straight on.
     */
    private fun enterStoreOrResume() {
        storeOptions = rollStoreOffers()
        if (storeOptions.isNotEmpty()) {
            status = RunStatus.STORE
        } else {
            resumeIntoNextSet()
        }
    }

    /** Four random offers drawn from the catalog minus everything already owned this run. */
    private fun rollStoreOffers(): List<StoreOffer> {
        val pool = StoreCatalog.ITEMS.filter { it.id !in runUnlockedIds }.toMutableList()
        val out = ArrayList<StoreOffer>(STORE_OFFER_COUNT)
        while (pool.isNotEmpty() && out.size < STORE_OFFER_COUNT) {
            val item = pool.removeAt(rng.nextInt(pool.size))
            out.add(StoreOffer(item.id, item.name, item.description, item.category.label, item.category.cost))
        }
        return out
    }

    /**
     * Apply a bought store item to this run and resume (DESIGN.md §9). The Modifier-Budget spend and
     * the permanent unlock record are handled by the caller against the bank — the engine never
     * touches banked resources (the same contract as [revive]). No-op unless paused on the store.
     */
    fun applyStorePurchase(itemId: String) {
        if (status != RunStatus.STORE) return
        StoreCatalog.byId(itemId)?.let { item ->
            when (item) {
                is StoreCatalog.Item.ArtifactItem -> grantArtifact(item.modifier)   // joins the build + pool
                is StoreCatalog.Item.GunItem -> swapWeapon(item.weapon)             // replaces the aimed weapon
                is StoreCatalog.Item.MutatorItem -> applyMutator(item)             // run-wide effect
            }
            runUnlockedIds.add(item.id)
        }
        resumeIntoNextSet()
    }

    /** Decline the store this set (DESIGN.md §9). No-op unless paused on the store. */
    fun skipStore() {
        if (status != RunStatus.STORE) return
        resumeIntoNextSet()
    }

    private fun resumeIntoNextSet() {
        storeOptions = emptyList()
        breatherTimer = BREATHER_SECONDS * 1.5f
        beginWave(0)
        status = RunStatus.RUNNING
    }

    /** Grant (or rank up) a store artifact immediately, so it's live for the rest of this run. */
    private fun grantArtifact(mod: Modifier) {
        val idx = player.held.indexOfFirst { it.modifier.id == mod.id }
        if (idx >= 0) {
            val next = (player.held[idx].rank + 1).coerceAtMost(mod.maxRank)
            player.held[idx] = HeldModifier(mod, next)
        } else {
            player.held.add(HeldModifier(mod, 1))
        }
        rebuildStats()
    }

    /** Swap the aimed weapon to a store gun: reset firing state and hand over a full magazine. */
    private fun swapWeapon(newWeapon: StartingWeapon) {
        player.weapon = newWeapon
        player.reloadRemaining = 0f
        player.spin = 0f
        player.fireCooldown = 0f
        rebuildStats()
        player.ammo = player.magazine(stats)
    }

    /** Fold a mutator's run-wide bonuses/banes in (DESIGN.md §9). Used at run start and on purchase. */
    private fun applyMutator(item: StoreCatalog.Item.MutatorItem) {
        player.runBonuses.addAll(item.playerBonuses)
        item.directorBanes.forEach { director.addRunBane(it) }
        rebuildStats()
    }

    /** The draft pool: the baseline artifacts plus every passive/equipment unlocked from the store
     *  (DESIGN.md §9). This is how a bought item "becomes part of the level-up pool" for the run. */
    private fun artifactPool(): List<Modifier> = Artifacts.ALL + StoreCatalog.unlockedArtifacts(runUnlockedIds)

    private fun rollLevelUpOptions(): List<LevelUpOption> {
        val candidates = artifactPool().mapNotNull { mod ->
            val held = player.held.firstOrNull { it.modifier.id == mod.id }
            val currentRank = held?.rank ?: 0
            if (currentRank >= mod.maxRank) null
            else {
                val resultingRank = currentRank + 1
                // Combat-equipment ranks past the first roll a random upgrade from *that equipment's*
                // pool (DESIGN.md §5/§9), so the offer shows exactly which improvement this pick grants.
                val pool = EquipmentUpgrades.poolFor(mod.id)
                val upgrade = if (mod.category == com.lifeops.app.game.core.ArtifactCategory.COMBAT_EQUIPMENT &&
                    resultingRank >= 2 && pool.isNotEmpty())
                    pool[rng.nextInt(pool.size)] else null
                LevelUpOption(mod, resultingRank, isNew = held == null, equipmentUpgrade = upgrade)
            }
        }
        if (candidates.isEmpty()) return emptyList()
        // Shuffle deterministically via the run RNG and take up to 3 distinct offers.
        val pool = candidates.toMutableList()
        val out = ArrayList<LevelUpOption>(3)
        while (pool.isNotEmpty() && out.size < 3) {
            out.add(pool.removeAt(rng.nextInt(pool.size)))
        }
        return out
    }

    private fun endRun(victory: Boolean) {
        if (status == RunStatus.VICTORY || status == RunStatus.DEFEAT) return
        status = if (victory) RunStatus.VICTORY else RunStatus.DEFEAT
        bus.emit(GameEvent.OnRunEnd(victory, score))
    }

    /**
     * Buy back into the same run after a defeat (DESIGN.md §7). Restores full hearts, grants a grace
     * window of invulnerability, and clears the swarm (and enemy fire) around the player so the
     * revive lands in breathing room instead of straight back into the pile that overran them.
     * Bosses are left standing — the i-frames are the time to reposition, not a free boss wipe.
     * The energy price is charged by the caller against the bank; the engine never touches banked
     * resources. No-op unless the run is currently in defeat.
     */
    fun revive() {
        if (status != RunStatus.DEFEAT) return
        revives++
        player.hits = player.maxHits
        player.invuln = REVIVE_INVULN
        player.hurtFlash = 0f
        enemies.removeAll { !it.type.isBoss && it.pos.distanceTo(player.pos) <= REVIVE_CLEAR_RADIUS }
        projectiles.removeAll { !it.friendly && it.pos.distanceTo(player.pos) <= REVIVE_CLEAR_RADIUS }
        status = RunStatus.RUNNING
    }

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
