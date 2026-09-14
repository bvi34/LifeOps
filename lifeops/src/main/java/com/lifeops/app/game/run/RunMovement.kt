package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Movement, and everything that holds still while things move past it.

 * The player's step, the enemies' beeline, and the structures they walk into: turrets, mines,
 * decoys and the outpost. Grouped by *who is moving where* rather than by what each thing is,
 * because a frame's movement has to resolve as one pass — a turret that fired before the enemy
 * moved and a mine that armed after it are two different frames.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

internal fun RunEngine.movePlayer(dt: Float, input: RunInput) {
    val dir = input.move.clampLength(1f)
    if (dir.length() <= Vec2.EPSILON) return
    val speed = player.moveSpeed(stats)
    player.pos = arena.clamp(player.pos + dir * (speed * dt), player.radius)
}

internal fun RunEngine.moveEnemies(dt: Float) {
    for (e in enemies) {
        // A Mother flees: she backs directly away from the player, staying alive to keep birthing.
        // Cornering her against the arena edge is how you finally pin her down (a Nest has moveSpeed
        // 0, so it just sits where it hatched — no special case needed).
        if (e.type.fleesPlayer) {
            val away = (e.pos - player.pos).normalized()
            moveEnemyToward(e, e.pos + away * (arena.cellSize * 4f), dt)
            continue
        }
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
internal fun RunEngine.moveEnemyToward(e: Enemy, target: Vec2, dt: Float) {
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
internal fun RunEngine.targetStructureFor(e: Enemy): Structure? {
    if (structures.isEmpty()) return null
    if (e.type == EnemyType.RUSHER) return nearestStructure(e.pos) { true }
    val decoy = nearestStructure(e.pos) { it.type == StructureType.DECOY } ?: return null
    val lure = stats.resolve(Stat.DECOY_RANGE)
    return if (e.pos.distanceTo(player.pos) > lure) decoy else null
}

internal inline fun RunEngine.nearestStructure(from: Vec2, predicate: (Structure) -> Boolean): Structure? {
    var best: Structure? = null
    var bestDist = Float.MAX_VALUE
    for (s in structures) {
        if (!s.alive || !predicate(s)) continue
        val d = from.distanceTo(s.pos)
        if (d < bestDist) { bestDist = d; best = s }
    }
    return best
}

internal fun RunEngine.attackStructure(s: Structure, e: Enemy) {
    if (e.attackCooldown > 0f) return
    e.attackCooldown = RunEngine.ATTACK_INTERVAL
    s.hp -= e.type.contactHits.toFloat() // durability is in hits; a blow removes contactHits
    bus.emit(GameEvent.OnHit(e.id, s.id, e.type.contactHits.toFloat(), false))
    // A reinforced wall (Outpost's BARRICADE_THORNS upgrade) bites back at whatever strikes it —
    // flat damage on every blow, routed through the resolver like any other hit (DESIGN.md §9).
    if (s.type == StructureType.BARRICADE) {
        val thorns = stats.resolve(Stat.BARRICADE_THORNS)
        if (thorns > 0f) resolver.resolve {
            e.health -= thorns
            e.hitFlash = RunEngine.HIT_FLASH_SECONDS
            bus.emit(GameEvent.OnHit(RunEngine.PLAYER_ID, e.id, thorns, false))
            if (!e.alive) killEnemy(e)
        }
    }
}

/** Ranged enemies (Spitter) fire bursts at the player: a few quick shots, then a long recovery. */
internal fun RunEngine.updateEnemyFire(dt: Float) {
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
internal fun RunEngine.updateStructures(dt: Float) {
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
                if (blast > 0f) explode(s.pos, blast, RunEngine.DECOY_BLAST_DAMAGE, directTargetId = -1)
            }
            effects.add(RunEffect(s.pos, EffectKind.DEATH_BURST, worldRadius = arena.cellSize * 0.5f, ttl = RunEngine.BURST_SECONDS))
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
internal fun RunEngine.fireTurretVolley(origin: Vec2, dir: Vec2, damage: Float, ownerId: Int, speed: Float, range: Float, count: Int) {
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
internal fun RunEngine.updateArtifactTurrets(dt: Float) {
    val desired = stats.resolve(Stat.TURRET_COUNT, Scope.AUTO).toInt()
    if (desired <= 0) return
    if (turretDeployTimer > 0f) turretDeployTimer -= dt
    val current = structures.count { it.artifactTurret }
    if (current < desired && turretDeployTimer <= 0f) {
        if (deployArtifactTurret()) turretDeployTimer = RunEngine.TURRET_DEPLOY_INTERVAL
    }
}

/** Deploy one auto-turret at the nearest free grid cell to the player. Returns whether it placed. */
internal fun RunEngine.deployArtifactTurret(): Boolean {
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
internal fun RunEngine.updateMines(dt: Float) {
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
        mineDeployTimer = RunEngine.MINE_DEPLOY_INTERVAL
    }
}

/** Sow one mine at a random in-arena point ringing the player. */
internal fun RunEngine.deployMine() {
    val ang = rng.nextFloat(0f, RunEngine.TWO_PI)
    val dist = rng.nextFloat(RunEngine.MINE_MIN_DIST, RunEngine.MINE_MAX_DIST)
    val pos = arena.clamp(Vec2(player.pos.x + cos(ang) * dist, player.pos.y + sin(ang) * dist), 6f)
    mines.add(Mine(id = nextId++, pos = pos, arming = RunEngine.MINE_ARM_TIME))
}

/**
 * The Decoy equipment (DESIGN.md §9): keep [Stat.DECOY_COUNT] decoys planted near the player,
 * replanting on a cooldown as enemies tear them down. Each is a non-blocking structure deployed
 * with build-scaled HP; the targeting layer routes distant aggro onto it.
 */
internal fun RunEngine.updateDecoys(dt: Float) {
    val desired = stats.resolve(Stat.DECOY_COUNT).toInt()
    if (desired <= 0) return
    if (decoyDeployTimer > 0f) decoyDeployTimer -= dt
    val current = structures.count { it.type == StructureType.DECOY }
    if (current < desired && decoyDeployTimer <= 0f) {
        if (deployDecoy()) decoyDeployTimer = RunEngine.DECOY_DEPLOY_INTERVAL
    }
}

/** Plant one decoy at the nearest free grid cell to the player, with build-scaled durability. */
internal fun RunEngine.deployDecoy(): Boolean {
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
internal fun RunEngine.updateOutpost(dt: Float) {
    val desired = stats.resolve(Stat.OUTPOST_COUNT).toInt()
    if (desired <= 0) return
    if (outpostDeployTimer > 0f) outpostDeployTimer -= dt
    // One Sentry anchors each emplacement; the paired Barricade rides along and isn't tallied.
    val current = structures.count { it.fromOutpost && it.type == StructureType.SENTRY }
    if (current < desired && outpostDeployTimer <= 0f) {
        if (deployOutpost()) outpostDeployTimer = RunEngine.OUTPOST_DEPLOY_INTERVAL
    }
}

/**
 * Plant one permanent Outpost: a Sentry at the nearest free cell to the player, and a Barricade one
 * cell beyond it — continuing the line away from the player, so it walls the emplacement's outer
 * face (barricade | sentry | player). If that cell is taken the Sentry still stands alone. Returns
 * whether the Sentry placed.
 */
internal fun RunEngine.deployOutpost(): Boolean {
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
internal fun RunEngine.plantOutpostStructure(type: StructureType, col: Int, row: Int) {
    val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
    val s = Structure(
        id = nextId++, type = type, col = col, row = row, pos = center,
        hp = type.maxHp, maxHp = type.maxHp, fromOutpost = true,
    )
    structures.add(s)
    occupancy[cellKey(col, row)] = s
}

/** Whether [col],[row] is an in-arena grid cell not already occupied by a structure. */
internal fun RunEngine.isFreeCell(col: Int, row: Int): Boolean {
    if (occupancy.containsKey(cellKey(col, row))) return false
    val center = Vec2((col + 0.5f) * arena.cellSize, (row + 0.5f) * arena.cellSize)
    return arena.contains(center)
}

/** Search grid cells outward from the player's cell for the first free, in-arena, non-player cell. */
internal fun RunEngine.freeCellNearPlayer(): Pair<Int, Int>? {
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
