package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution
import com.lifeops.app.game.core.Vec2
import kotlin.math.roundToInt

/**
 * The wave director: what to spawn, when, and where the arena's edge lets it in.
 *
 * Also the bosses on the final wave and the breather between sets. Nothing here touches combat —
 * a wave's job ends the moment the enemy exists.
 *
 * Extensions on [RunEngine] rather than a class of their own: one frame is one pass over one
 * set of mutable state, and handing each system its own copy of the arena — or a reference back
 * to the engine to reach the real one — buys indirection and no isolation. What the split buys
 * is that a file is now one question.
 */

internal fun RunEngine.beginWave(index: Int) {
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

internal fun RunEngine.isFinalWave(): Boolean = wave >= config.waves - 1

internal fun RunEngine.updateWaves(dt: Float) {
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
            spawnTimer = RunEngine.SPAWN_INTERVAL
        }
    }
    // Bosses roll in once the final wave's trash is half out, dripping so they don't stack at once.
    if (isFinalWave() && bossesSpawned < bossesToSpawn && spawnedThisWave >= toSpawnThisWave / 2) {
        bossTimer -= dt
        if (bossTimer <= 0f) {
            spawnEnemy(pendingBosses[bossesSpawned], hpScale = 1f)
            bossesSpawned++
            bossTimer = RunEngine.BOSS_STAGGER
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
                breatherTimer = RunEngine.BREATHER_SECONDS * 1.5f
                beginWave(0)
            }
        } else {
            breatherTimer = RunEngine.BREATHER_SECONDS
            beginWave(wave + 1)
        }
    }
}

internal fun RunEngine.spawnWaveEnemy() {
    // Weighted pick among the trash archetypes unlocked at the current tier (each tier reveals a
    // new one). Bosses are never in this pool — they come from the final-wave roster.
    spawnEnemy(weightedTrashPick { true }, hpScale = 1f)
}

/**
 * A weighted-random trash archetype unlocked at the current [tier], further narrowed by [extra]
 * (e.g. a Mother's brood excludes other spawners so nests don't hatch nests). Bosses never qualify.
 */
internal inline fun RunEngine.weightedTrashPick(extra: (EnemyType) -> Boolean): EnemyType {
    val pool = EnemyType.values().filter { !it.isBoss && it.unlockTier <= tier && extra(it) }
    val totalWeight = pool.sumOf { it.spawnWeight }
    var r = rng.nextInt(totalWeight.coerceAtLeast(1))
    var chosen = pool.first()
    for (t in pool) {
        r -= t.spawnWeight
        if (r < 0) { chosen = t; break }
    }
    return chosen
}

/**
 * On-field spawners (Nest, Mother — DESIGN.md §7). While one lives it births minions beside itself
 * on its own cadence, capped at [EnemyType.maxBrood] live children. A Nest hatches Shamblers where
 * it sits; a Mother births a random unlocked trash type as she flees. A Mother's brood death resets
 * her timer (see [killEnemy]), so thinning her swarm only makes her spawn faster.
 */
internal fun RunEngine.updateSpawners(dt: Float) {
    // Snapshot first: a birth appends to [enemies], which must not be mutated mid-iteration.
    val active = enemies.filter { it.alive && it.type.isSpawner }
    for (e in active) {
        e.spawnCooldown -= dt
        if (e.spawnCooldown > 0f) continue
        e.spawnCooldown = e.type.spawnInterval // recharge whether or not the brood is full this tick
        val liveBrood = enemies.count { it.parentId == e.id && it.alive }
        if (liveBrood >= e.type.maxBrood) continue // brood at capacity — hold until one dies or strays
        val child = if (e.type.broodRandom) weightedTrashPick { !it.isSpawner } else EnemyType.SHAMBLER
        spawnEnemy(child, hpScale = 1f, at = e.pos, parentId = e.id)
    }
}

/**
 * The cumulative boss roster for [tier]: every boss whose unlockTier is reached, each repeated
 * [EnemyType.bossCount] times, ordered by unlock. Past the last defined boss, extra Abominations
 * keep the finale escalating.
 */
internal fun RunEngine.bossRosterFor(tier: Int): List<EnemyType> {
    val bosses = EnemyType.values().filter { it.isBoss }
    val roster = ArrayList<EnemyType>()
    bosses.filter { it.unlockTier <= tier }.sortedBy { it.unlockTier }.forEach { b ->
        repeat(b.bossCount) { roster.add(b) }
    }
    val maxBossTier = bosses.maxOf { it.unlockTier }
    if (tier > maxBossTier) repeat(tier - maxBossTier) { roster.add(EnemyType.ABOMINATION) }
    return roster
}


internal fun RunEngine.spawnEnemy(type: EnemyType, hpScale: Float, at: Vec2? = null, parentId: Int = -1) {
    // Brood minions hatch next to their parent (with a little scatter so they don't stack); a wave
    // enemy enters at a random active edge.
    val pos = if (at != null) {
        val j = arena.cellSize
        arena.clamp(Vec2(at.x + rng.nextFloat(-j, j), at.y + rng.nextFloat(-j, j)), type.radius)
    } else spawnPoint()
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
        // A spawner waits one full interval before its first birth, so it isn't instant on arrival.
        spawnCooldown = type.spawnInterval,
        parentId = parentId,
    )
    enemies.add(e)
    bus.emit(GameEvent.OnSpawn(e.id, e.kind))
}

/** Enemies enter at a random point on the current active arena edge. */
internal fun RunEngine.spawnPoint(): Vec2 = arena.randomEdge(rng)
