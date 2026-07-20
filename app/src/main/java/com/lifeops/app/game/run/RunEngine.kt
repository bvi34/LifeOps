package com.lifeops.app.game.run

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.SetBonuses
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.content.TempBoosts
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
    )

    val enemies = ArrayList<Enemy>()
    val projectiles = ArrayList<Projectile>()
    val pickups = ArrayList<Pickup>()
    val effects = ArrayList<RunEffect>()
    val structures = ArrayList<Structure>()
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

    private var stats: StatBlock = player.buildStats()
    private var nextId = 1

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

    init {
        director.configure(config.challengeMode, stats)
        beginWave(0)
    }

    private fun rebuildStats() {
        stats = player.buildStats()
        // Keep the Director tracking the player's build so Mirror inflates in lockstep (§8).
        director.rebuild(stats)
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
        updateStructures(clamped)
        moveProjectiles(clamped)
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
        wave = wave + 1,
        totalWaves = config.waves,
        tier = tier,
        score = score,
        status = status,
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
        structures = structures.map { StructureView(it.pos, it.type, (it.hp / it.maxHp).coerceIn(0f, 1f), it.aim) },
        structureCosts = StructureType.values().associateWith { buildCost(it) },
        pickups = pickups.map { PickupView(it.pos, it.kind) },
        effects = effects.map { EffectView(it.pos, it.kind, (it.age / it.ttl).coerceIn(0f, 1f), it.worldRadius) },
        boss = enemies.firstOrNull { it.kind == EntityKind.BOSS }
            ?.let { BossView((it.health / it.maxHealth).coerceIn(0f, 1f), it.type.displayName) },
        levelUpOptions = levelUpOptions,
        setBonusOptions = setBonusOptions,
        overflowOptions = overflowOptions,
        weapon = config.weapon,
        weaponName = config.weapon.displayName,
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
            // Open arena: beeline straight at the player (Geometry Wars), bounded by the active edges,
            // but blocked by placed structures — which the enemy attacks instead of walking through.
            val dir = (player.pos - e.pos).normalized()
            val delta = dir * (e.moveSpeed * dt)
            var p = e.pos
            val here = structureAt(p)
            val tryX = Vec2(p.x + delta.x, p.y)
            val sX = structureAt(tryX)
            if (sX != null && sX !== here && sX.type.blocks) attackStructure(sX, e) else p = tryX
            val tryY = Vec2(p.x, p.y + delta.y)
            val sY = structureAt(tryY)
            if (sY != null && sY !== here && sY.type.blocks) attackStructure(sY, e) else p = Vec2(p.x, tryY.y)
            e.pos = arena.clamp(p, e.type.radius)
        }
    }

    private fun attackStructure(s: Structure, e: Enemy) {
        if (e.attackCooldown > 0f) return
        e.attackCooldown = ATTACK_INTERVAL
        s.hp -= e.type.contactHits.toFloat() // durability is in hits; a blow removes contactHits
        bus.emit(GameEvent.OnHit(e.id, s.id, e.type.contactHits.toFloat(), false))
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

    /** Turrets auto-fire at the nearest enemy in range; dead structures are cleared. */
    private fun updateStructures(dt: Float) {
        val it = structures.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (!s.alive) {
                occupancy.remove(cellKey(s.col, s.row))
                effects.add(RunEffect(s.pos, EffectKind.DEATH_BURST, worldRadius = arena.cellSize * 0.5f, ttl = BURST_SECONDS))
                it.remove()
                continue
            }
            if (!s.type.isTurret) continue
            s.fireCooldown -= dt
            var target: Enemy? = null
            var bestDist = s.type.range
            for (en in enemies) {
                val d = en.pos.distanceTo(s.pos)
                if (d <= bestDist) { bestDist = d; target = en }
            }
            val t = target ?: continue
            s.aim = (t.pos - s.pos).normalized()
            if (s.fireCooldown <= 0f) {
                s.fireCooldown = 1f / s.type.fireRate
                spawnFriendlyProjectile(s.pos, s.aim, s.type.damage, s.id, s.type.projectileSpeed, s.type.range)
            }
        }
    }

    private fun spawnFriendlyProjectile(origin: Vec2, dir: Vec2, damage: Float, ownerId: Int, speed: Float, range: Float) {
        val p = Projectile(
            id = nextId++, ownerId = ownerId, pos = origin, vel = dir * speed,
            damage = damage, crit = false, lifeRemaining = range / speed, friendly = true,
        )
        projectiles.add(p)
        bus.emit(GameEvent.OnProjectileSpawn(p.id, ownerId))
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
        player.fireCooldown -= dt
        if (player.fireCooldown > 0f) return

        val aimDir = resolveAim(input) ?: return // no target and no manual aim → hold fire
        player.fireCooldown = 1f / player.fireRate(stats)

        val count = player.projectiles(stats)
        var perHit = player.aimedDamage(stats)
        // Gatling normalizes total DPS across projectile count (DESIGN.md §4): splitting into more
        // projectiles must not multiply throughput, so per-hit damage is divided by the count.
        if (config.weapon.dpsNormalized && count > 1) perHit /= count.toFloat()

        val baseAngle = kotlin.math.atan2(aimDir.y, aimDir.x)
        val spread = if (count > 1) 0.28f else 0f // ~16° fan
        val speed = player.projectileSpeed(stats)
        val life = player.range(stats) / speed
        val critChance = player.critChance(stats)
        val critMult = player.critMult(stats)

        for (i in 0 until count) {
            val t = if (count == 1) 0f else (i / (count - 1f)) - 0.5f
            val angle = baseAngle + t * spread
            val vel = Vec2(cos(angle), sin(angle)) * speed
            val crit = rng.chance(critChance)
            val dmg = if (crit) perHit * critMult else perHit
            val p = Projectile(
                id = nextId++, ownerId = PLAYER_ID, pos = player.pos, vel = vel,
                damage = dmg, crit = crit, lifeRemaining = life, friendly = true,
            )
            projectiles.add(p)
            bus.emit(GameEvent.OnProjectileSpawn(p.id, PLAYER_ID))
        }
        player.muzzleFlash = MUZZLE_SECONDS
    }

    /** Track the aim direction every frame so the barrel/reticle follows the nearest target even
     *  between shots. Keeps the previous aim when there is no target, so the barrel never snaps. */
    private fun updateAim(input: RunInput) {
        resolveAim(input)?.let { player.aim = it }
    }

    /** Manual aim wins if given; otherwise auto-aim the nearest enemy within range (DESIGN.md §4). */
    private fun resolveAim(input: RunInput): Vec2? {
        input.aimOverride?.let { if (it.length() > Vec2.EPSILON) return it.normalized() }
        val range = player.range(stats)
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

    // --- Collisions -------------------------------------------------------------------------

    private fun resolveProjectileHits() {
        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (!p.friendly) continue // only player/turret shots damage enemies
            var hitEnemy: Enemy? = null
            for (e in enemies) {
                if (p.pos.distanceTo(e.pos) <= e.type.radius + p.radius) { hitEnemy = e; break }
            }
            val target = hitEnemy ?: continue
            // Every hit is an event routed through the resolver's frame budget (invariants #2/#3).
            resolver.resolve {
                target.health -= p.damage
                target.hitFlash = HIT_FLASH_SECONDS
                bus.emit(GameEvent.OnHit(PLAYER_ID, target.id, p.damage, p.crit))
                if (!target.alive) killEnemy(target)
            }
            pit.remove()
        }
        enemies.removeAll { !it.alive }
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
        breatherTimer = BREATHER_SECONDS * 1.5f
        beginWave(0)
        status = RunStatus.RUNNING
    }

    private fun rollLevelUpOptions(): List<LevelUpOption> {
        val candidates = Artifacts.ALL.mapNotNull { mod ->
            val held = player.held.firstOrNull { it.modifier.id == mod.id }
            val currentRank = held?.rank ?: 0
            if (currentRank >= mod.maxRank) null
            else LevelUpOption(mod, currentRank + 1, isNew = held == null)
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

    companion object {
        const val PLAYER_ID = 0
        const val SPAWN_INTERVAL = 0.30f   // faster drip — the open arena wants a real swarm
        const val BREATHER_SECONDS = 2.5f
        const val BOSS_STAGGER = 1.2f      // seconds between multiple bosses entering
        const val HIT_FLASH_SECONDS = 0.09f
        const val BURST_SECONDS = 0.35f
        const val MUZZLE_SECONDS = 0.06f
        const val HURT_SECONDS = 0.16f
        const val INVULN_SECONDS = 0.8f    // i-frames after a hit — one contact = one heart
        const val ATTACK_INTERVAL = 0.6f   // seconds between an enemy's blows on a structure
    }
}
