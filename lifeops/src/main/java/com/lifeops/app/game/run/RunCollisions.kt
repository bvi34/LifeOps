package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.Vec2

/**
 * What happens when two things touch: a shot landing, a body meeting the player, a splash going
 * off, and the death that follows.
 *
 * Every combat moment in here goes through the [com.lifeops.app.game.core.EventBus] and every
 * effect through the [com.lifeops.app.game.core.EffectResolver] budget — DESIGN.md invariants #2
 * and #3 live in this file more than anywhere else.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

internal fun RunEngine.resolveProjectileHits() {
    // Ricochets spawn new shots; they're collected here and added after the pass, since we're
    // iterating the same list.
    val ricochets = ArrayList<Projectile>()
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
            t.hitFlash = RunEngine.HIT_FLASH_SECONDS
            p.hitIds.add(t.id)
            bus.emit(GameEvent.OnHit(RunEngine.PLAYER_ID, t.id, p.damage, p.crit))
            if (!t.alive) killEnemy(t)
            if (p.explosionRadius > 0f) explode(p.pos, p.explosionRadius, p.damage * RunEngine.EXPLOSION_DAMAGE_FRAC, t.id)
        }
        // Survive the hit via pierce first (straight through), then ricochet — which retires this
        // shot and fires a fresh one at a new target. A shot with neither budget is spent on impact.
        when {
            p.pierceLeft > 0 -> p.pierceLeft--
            p.bouncesLeft > 0 -> {
                pit.remove()
                spawnRicochet(p)?.let { ricochets.add(it) }
            }
            else -> pit.remove()
        }
    }
    for (r in ricochets) {
        projectiles.add(r)
        bus.emit(GameEvent.OnProjectileSpawn(r.id, r.ownerId))
    }
    enemies.removeAll { !it.alive }
}

/**
 * An explosive shot's blast (DESIGN.md §9): splash [damage] to every enemy within [radius] of
 * [center], skipping the projectile's direct target (already damaged this hit). Each splash hit
 * goes through the resolver so a chain of explosions still respects the frame budget.
 */
internal fun RunEngine.explode(center: Vec2, radius: Float, damage: Float, directTargetId: Int) {
    effects.add(RunEffect(pos = center, kind = EffectKind.EXPLOSION, worldRadius = radius, ttl = RunEngine.BURST_SECONDS))
    for (e in enemies) {
        if (!e.alive || e.id == directTargetId) continue
        if (center.distanceTo(e.pos) > radius + e.type.radius) continue
        resolver.resolve {
            e.health -= damage
            e.hitFlash = RunEngine.HIT_FLASH_SECONDS
            bus.emit(GameEvent.OnHit(RunEngine.PLAYER_ID, e.id, damage, false))
            if (!e.alive) killEnemy(e)
        }
    }
}

/**
 * A ricochet (DESIGN.md §9): the spent shot [p] is retired and a **new** shot is fired from where
 * it landed at the nearest enemy the chain hasn't struck yet, carrying its damage, crit and on-hit
 * passives with one bounce spent.
 *
 * Spawning rather than redirecting is what makes ricochet pay off next to the other on-hit
 * passives: the new shot re-rolls the full [Projectile.pierce] budget (and still explodes, and
 * still bounces), so a bounce is a whole extra shot's worth of behaviour instead of a shot that
 * already spent itself passing through bodies. With ricochet alone it looks the same as a bounce.
 *
 * Returns null when no fresh target is left to bounce to — the chain simply ends there.
 */
internal fun RunEngine.spawnRicochet(p: Projectile): Projectile? {
    var best: Enemy? = null
    var bestDist = Float.MAX_VALUE
    for (e in enemies) {
        if (!e.alive || e.id in p.hitIds) continue
        val d = e.pos.distanceTo(p.pos)
        if (d < bestDist) { bestDist = d; best = e }
    }
    val target = best ?: return null
    val speed = p.vel.length()
    return Projectile(
        id = nextId++, ownerId = p.ownerId, pos = p.pos,
        vel = (target.pos - p.pos).normalized() * speed,
        damage = p.damage, crit = p.crit,
        // A fresh leg of travel, so a bounced shot doesn't die to the previous target's range clock.
        lifeRemaining = maxOf(p.lifeRemaining, RunEngine.RICOCHET_LIFE),
        friendly = p.friendly, radius = p.radius,
        pierce = p.pierce, bouncesLeft = p.bouncesLeft - 1, explosionRadius = p.explosionRadius,
        hitIds = p.hitIds, // shared: the bounce chain never doubles back on an earlier leg's target
    )
}

internal fun RunEngine.killEnemy(e: Enemy) {
    score += e.type.xpValue * 10L
    bus.emit(GameEvent.OnKill(RunEngine.PLAYER_ID, e.id, e.kind))
    // Feeding the Mother: if this enemy was one of her brood, her spawn timer zeroes so she births
    // again immediately — thinning the swarm only accelerates her. The dying child is still in the
    // list here (removed after this pass), but the live-parent lookup skips it.
    if (e.parentId >= 0) {
        enemies.firstOrNull { it.id == e.parentId && it.alive && it.type.broodResetsOnDeath }
            ?.let { it.spawnCooldown = 0f }
    }
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
    effects.add(RunEffect(pos = e.pos, kind = EffectKind.DEATH_BURST, worldRadius = e.type.radius, ttl = RunEngine.BURST_SECONDS))
}

/** Age hit-flash / i-frame / attack timers and transient effects; drop expired. Visual + gameplay timers. */
internal fun RunEngine.ageVisuals(dt: Float) {
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
internal fun RunEngine.resolveContact(dt: Float) {
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

internal fun RunEngine.hurtPlayer(hearts: Int) {
    player.hits -= hearts
    player.invuln = RunEngine.INVULN_SECONDS
    player.hurtFlash = RunEngine.HURT_SECONDS
    bus.emit(GameEvent.OnHit(-1, RunEngine.PLAYER_ID, hearts.toFloat(), false))
}
