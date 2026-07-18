package com.lifeops.app.game.run

import com.lifeops.app.game.content.Artifacts
import com.lifeops.app.game.content.EnemyType
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
import com.lifeops.app.game.map.MapState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pure, deterministic run simulation. Given a fixed [RunConfig] and the same [RunInput]
 * sequence it produces identical frames on JVM and device — the ViewModel only supplies timing
 * and reads snapshots. Every combat moment goes through the [EventBus]; every effect goes through
 * the [EffectResolver] budget (DESIGN.md invariants #2, #3). No android.* here.
 *
 * Wave structure follows §7: [RunConfig.waves] waves (one per day of the closed week), a boss on
 * the final wave, short breathers between waves where — once the shop lands — gold gets spent.
 */
class RunEngine(
    val config: RunConfig,
    val bus: EventBus = EventBus(),
    private val resolver: EffectResolver = EffectResolver(),
) {
    /** Runtime map state (unlocked rooms, open doors, flow field). Geometry lives in [config.map]. */
    val mapState = MapState(config.map)
    val arena: Vec2 = config.map.worldSize
    private val rng = RunSeed(config.seed)

    val player = Player(
        pos = config.map.cellCenter(config.map.startCol, config.map.startRow),
        weapon = config.weapon,
        health = config.maxHealth,
        maxHealth = config.maxHealth,
        gold = config.startingGold,
    )

    val enemies = ArrayList<Enemy>()
    val projectiles = ArrayList<Projectile>()
    val pickups = ArrayList<Pickup>()
    val effects = ArrayList<RunEffect>()

    var status: RunStatus = RunStatus.RUNNING
        private set
    var score: Long = 0L
        private set
    var wave: Int = 0
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
    private var bossSpawned = false

    private var levelUpOptions: List<LevelUpOption> = emptyList()

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
        mapState.computeFlow(config.map.colOf(player.pos.x), config.map.rowOf(player.pos.y))
        updateWaves(clamped)
        moveEnemies(clamped)
        updateAim(input)
        fireWeapon(clamped, input)
        moveProjectiles(clamped)
        resolveProjectileHits()
        resolveTouchDamage(clamped)
        updatePickups(clamped)
        ageVisuals(clamped)

        if (player.health <= 0f) endRun(victory = false)
    }

    /**
     * Buy the given barrier if the player is standing near it and can afford it: spend in-run gold
     * and open the door (a §7 gold sink). No-op otherwise. Returns whether the purchase happened.
     */
    fun buyBarrier(barrierId: Int): Boolean {
        if (status != RunStatus.RUNNING) return false
        val barrier = config.map.barriers.firstOrNull { it.id == barrierId } ?: return false
        if (barrier.id in mapState.openBarriers) return false
        val near = mapState.nearbyLockedBarrier(player.pos, INTERACT_DIST)
        if (near?.id != barrier.id) return false
        if (player.gold < barrier.cost) return false
        player.gold -= barrier.cost
        mapState.unlock(barrier.id)
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
        arena = arena,
        playerPos = player.pos,
        playerHealth = player.health,
        playerMaxHealth = player.maxHealth,
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
        projectiles = projectiles.map { it.pos },
        pickups = pickups.map { PickupView(it.pos, it.kind) },
        effects = effects.map { EffectView(it.pos, it.kind, (it.age / it.ttl).coerceIn(0f, 1f), it.worldRadius) },
        boss = enemies.firstOrNull { it.kind == EntityKind.BOSS }
            ?.let { BossView((it.health / it.maxHealth).coerceIn(0f, 1f), it.type.displayName) },
        levelUpOptions = levelUpOptions,
        weapon = config.weapon,
        weaponName = config.weapon.displayName,
        challengeModeName = config.challengeMode.name,
        held = player.held.map { HeldView(it.modifier.name, it.rank, it.modifier.maxRank) },
        map = config.map,
        unlockedZoneIds = mapState.unlockedZones.toSet(),
        openBarrierIds = mapState.openBarriers.toSet(),
        nearbyBarrier = mapState.nearbyLockedBarrier(player.pos, INTERACT_DIST)?.let {
            BarrierPrompt(it.id, it.name, it.cost, player.gold >= it.cost)
        },
    )

    // --- Movement ---------------------------------------------------------------------------

    private fun movePlayer(dt: Float, input: RunInput) {
        val dir = input.move.clampLength(1f)
        if (dir.length() <= Vec2.EPSILON) return
        val speed = player.moveSpeed(stats)
        player.pos = slideMove(player.pos, dir * (speed * dt))
    }

    private fun moveEnemies(dt: Float) {
        for (e in enemies) {
            // Descend the flow field so enemies funnel through open doorways instead of clipping walls.
            val dir = mapState.flowDir(e.pos, player.pos)
            e.pos = slideMove(e.pos, dir * (e.moveSpeed * dt))
        }
    }

    /**
     * Move [from] by [delta] against the map's walls, sliding along a wall rather than sticking:
     * each axis is applied only if the resulting cell is walkable. Keeps the player and enemies
     * inside unlocked rooms and out of the walls (DESIGN.md §7 holdout geometry).
     */
    private fun slideMove(from: Vec2, delta: Vec2): Vec2 {
        var p = from
        val tryX = Vec2(p.x + delta.x, p.y)
        if (mapState.walkableWorld(tryX)) p = tryX
        val tryY = Vec2(p.x, p.y + delta.y)
        if (mapState.walkableWorld(tryY)) p = tryY
        return p
    }

    // --- Waves ------------------------------------------------------------------------------

    private fun beginWave(index: Int) {
        wave = index
        spawnedThisWave = 0
        bossSpawned = false
        spawnTimer = 0f
        // Enemy budget grows per wave; the final wave is lighter on trash to make room for the boss.
        // The Director's spawn multiplier (e.g. Mirror mode) scales the whole wave.
        val baseBudget = if (isFinalWave()) 6 + index else 8 + index * 3
        toSpawnThisWave = (baseBudget * director.spawnMult()).roundToInt().coerceAtLeast(1)
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
        // Boss appears once the final wave's trash is out.
        if (isFinalWave() && !bossSpawned && spawnedThisWave >= toSpawnThisWave / 2) {
            spawnBoss()
            bossSpawned = true
        }
        // Wave clears when everything spawned is dead.
        val doneSpawning = spawnedThisWave >= toSpawnThisWave && (!isFinalWave() || bossSpawned)
        if (doneSpawning && enemies.isEmpty()) {
            score += 100L * (wave + 1)
            if (isFinalWave()) {
                endRun(victory = true)
            } else {
                breatherTimer = BREATHER_SECONDS
                beginWave(wave + 1)
            }
        }
    }

    private fun spawnWaveEnemy() {
        // Elites (Husk) grow more common in later waves; early waves are mostly trash.
        val eliteChance = 0.08f + wave * 0.04f
        val type = if (rng.chance(eliteChance)) EnemyType.HUSK else EnemyType.SHAMBLER
        spawnEnemy(type, hpScale = 1f)
    }

    private fun spawnBoss() = spawnEnemy(EnemyType.ABOMINATION, hpScale = 1f)

    private fun spawnEnemy(type: EnemyType, hpScale: Float) {
        val pos = spawnPoint() ?: return
        // Build the enemy through the same StatBlock path the player uses: its archetype base, then
        // the Director's per-enemy multipliers, then any enemy-attached artifacts (Mob Boss). An
        // aimed-scope artifact is simply inert on an enemy with no weapon (DESIGN.md §3).
        val block = StatBlock(
            mapOf(
                Stat.MAX_HEALTH to type.maxHealth,
                Stat.MOVE_SPEED to type.moveSpeed,
                Stat.DAMAGE to type.touchDamage,
            )
        )
        block.add(StatContribution(Stat.MAX_HEALTH, Scope.GLOBAL, Op.MULTIPLY, director.enemyHpMult()))
        block.add(StatContribution(Stat.MOVE_SPEED, Scope.GLOBAL, Op.MULTIPLY, director.enemySpeedMult()))
        block.add(StatContribution(Stat.DAMAGE, Scope.GLOBAL, Op.MULTIPLY, director.enemyDamageMult()))
        director.enemyModifiers.forEach { block.addAll(it.contributions()) }

        val hp = block.resolve(Stat.MAX_HEALTH, Scope.GLOBAL) * hpScale
        val e = Enemy(
            id = nextId++,
            type = type,
            pos = pos,
            health = hp,
            maxHealth = hp,
            moveSpeed = block.resolve(Stat.MOVE_SPEED, Scope.GLOBAL),
            touchDamage = block.resolve(Stat.DAMAGE, Scope.GLOBAL),
            held = director.enemyModifiers,
        )
        enemies.add(e)
        bus.emit(GameEvent.OnSpawn(e.id, e.kind))
    }

    /** Enemies climb in through a random window of an unlocked room (DESIGN.md §7). */
    private fun spawnPoint(): Vec2? {
        val active = mapState.activeEntrances()
        if (active.isEmpty()) return null
        val e = active[rng.nextInt(active.size)]
        return config.map.cellCenter(e.col, e.row)
    }

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
                damage = dmg, crit = crit, lifeRemaining = life,
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
        p.x < -16f || p.y < -16f || p.x > arena.x + 16f || p.y > arena.y + 16f

    // --- Collisions -------------------------------------------------------------------------

    private fun resolveProjectileHits() {
        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (p.ownerId != PLAYER_ID) continue
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
        pickups.add(Pickup(nextId++, e.pos, PickupKind.XP, e.type.xpValue))
        if (e.type.goldValue > 0 && rng.chance(0.5f)) {
            pickups.add(Pickup(nextId++, e.pos, PickupKind.GOLD, e.type.goldValue))
        }
        if (e.kind == EntityKind.BOSS) {
            pickups.add(Pickup(nextId++, e.pos, PickupKind.HEALTH, 30))
        }
        effects.add(RunEffect(pos = e.pos, kind = EffectKind.DEATH_BURST, worldRadius = e.type.radius, ttl = BURST_SECONDS))
    }

    /** Age hit-flash timers and transient effects; drop anything that has expired. Visual only. */
    private fun ageVisuals(dt: Float) {
        for (e in enemies) if (e.hitFlash > 0f) e.hitFlash = (e.hitFlash - dt).coerceAtLeast(0f)
        if (player.muzzleFlash > 0f) player.muzzleFlash = (player.muzzleFlash - dt).coerceAtLeast(0f)
        if (player.hurtFlash > 0f) player.hurtFlash = (player.hurtFlash - dt).coerceAtLeast(0f)
        val it = effects.iterator()
        while (it.hasNext()) {
            val fx = it.next()
            fx.age += dt
            if (fx.age >= fx.ttl) it.remove()
        }
    }

    private fun resolveTouchDamage(dt: Float) {
        for (e in enemies) {
            if (e.pos.distanceTo(player.pos) <= e.type.radius + player.radius) {
                val dmg = e.touchDamage * dt
                player.health -= dmg
                player.hurtFlash = HURT_SECONDS
                bus.emit(GameEvent.OnHit(e.id, PLAYER_ID, dmg, false))
            }
        }
    }

    // --- Pickups & leveling -----------------------------------------------------------------

    private fun updatePickups(dt: Float) {
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
                player.health = (player.health + pk.amount).coerceAtMost(player.maxHealth)
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
                // A real level-up pauses the run for a pick; stop draining XP until resumed.
                if (status == RunStatus.LEVEL_UP) break
            } else {
                overflowLevel()
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

    /** At cap the XP bar keeps filling; each fill is an instant in-run reward (DESIGN.md §6). */
    private fun overflowLevel() {
        player.xpToNext *= 1.15f
        bus.emit(GameEvent.OnLevelUp(player.level, overflow = true))
        score += 50L
        when (rng.nextInt(3)) {
            0 -> player.health = (player.health + 12f).coerceAtMost(player.maxHealth)
            1 -> player.gold += 3 // overflow gold dies with the run — never banked (invariant #1)
            else -> score += 40L
        }
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
        const val SPAWN_INTERVAL = 0.55f
        const val BREATHER_SECONDS = 2.5f
        const val HIT_FLASH_SECONDS = 0.09f
        const val BURST_SECONDS = 0.35f
        const val MUZZLE_SECONDS = 0.06f
        const val HURT_SECONDS = 0.16f
        /** How close (world units) the player must be to a locked door to buy it. */
        const val INTERACT_DIST = 42f
    }
}
