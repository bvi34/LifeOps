package com.lifeops.app.game.run

import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aiming and firing: the player's weapon, the projectiles it puts in the air, and the shots an
 * enemy's projectile is stopped by on the way back.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

internal fun RunEngine.fireWeapon(dt: Float, input: RunInput) {
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
    val life = if (weapon.unlimitedRange) RunEngine.UNLIMITED_LIFE else player.range(stats) / speed
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
            id = nextId++, ownerId = RunEngine.PLAYER_ID, pos = player.pos, vel = vel,
            damage = dmg, crit = crit, lifeRemaining = life, friendly = true,
            pierceLeft = pierce, bouncesLeft = bounces, explosionRadius = boom,
        )
        projectiles.add(p)
        bus.emit(GameEvent.OnProjectileSpawn(p.id, RunEngine.PLAYER_ID))
    }
    player.muzzleFlash = RunEngine.MUZZLE_SECONDS
    if (player.ammo <= 0) beginReload() // that was the last round — start the auto-reload now
}

/**
 * Begin a reload if one isn't already running and the magazine isn't full (DESIGN.md §4). The
 * time taken is the weapon's base reload shortened by the Autoloader's RELOAD_SPEED multiplier.
 */
internal fun RunEngine.beginReload() {
    if (player.reloadRemaining > 0f || player.ammo >= player.magazine(stats)) return
    player.reloadRemaining = weapon.reloadSeconds / player.reloadSpeed(stats)
}

/** Reload on demand (DESIGN.md §4). Ignored while paused/over, mid-reload, or on a full magazine. */
internal fun RunEngine.reload() {
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
internal fun RunEngine.effectiveFireRate(): Float {
    val rate = player.fireRate(stats)
    if (weapon.spinUpAccel <= 0f) return rate
    val baseRate = (weapon.baseStats[Stat.FIRE_RATE] ?: rate).coerceAtLeast(0.01f)
    val ramp = (weapon.spinUpFloor + weapon.spinUpAccel * player.spin).coerceAtLeast(0.1f)
    return ramp * (rate / baseRate)
}

/** Track the aim direction every frame so the barrel/reticle follows the nearest target even
 *  between shots. Keeps the previous aim when there is no target, so the barrel never snaps. */
internal fun RunEngine.updateAim(input: RunInput) {
    resolveAim(input)?.let { player.aim = it }
}

/** Manual aim wins if given; otherwise auto-aim the nearest enemy within range (DESIGN.md §4). */
internal fun RunEngine.resolveAim(input: RunInput): Vec2? {
    input.aimOverride?.let { if (it.length() > Vec2.EPSILON) return it.normalized() }
    // The Sniper locks on anywhere (unlimited range, §4); the others only auto-aim within reach.
    val range = if (weapon.unlimitedRange) RunEngine.UNLIMITED_AIM_RANGE else player.range(stats)
    var best: Enemy? = null
    var bestDist = Float.MAX_VALUE
    for (e in enemies) {
        val d = e.pos.distanceTo(player.pos)
        if (d <= range && d < bestDist) { best = e; bestDist = d }
    }
    return best?.let { (it.pos - player.pos).normalized() }
}

internal fun RunEngine.moveProjectiles(dt: Float) {
    val it = projectiles.iterator()
    while (it.hasNext()) {
        val p = it.next()
        p.pos = p.pos + p.vel * dt
        p.lifeRemaining -= dt
        if (p.lifeRemaining <= 0f || outOfArena(p.pos)) it.remove()
    }
}

internal fun RunEngine.outOfArena(p: Vec2): Boolean =
    p.x < -16f || p.y < -16f || p.x > arena.worldSize.x + 16f || p.y > arena.worldSize.y + 16f

/**
 * Enemy shots are stopped dead by a blocking structure's cell (barricade / static Sentry) — a wall
 * actually walls off incoming fire, so a Spitter can't shoot through it (DESIGN.md §7). Friendly
 * shots pass over your own defenses so a Sentry never blocks its own (or the player's) fire.
 */
internal fun RunEngine.resolveEnemyProjectileBlocks() {
    if (structures.isEmpty()) return
    val it = projectiles.iterator()
    while (it.hasNext()) {
        val p = it.next()
        if (p.friendly) continue
        val s = structureAt(p.pos) ?: continue
        if (s.type.blocks) it.remove()
    }
}
