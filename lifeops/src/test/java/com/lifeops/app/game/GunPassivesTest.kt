package com.lifeops.app.game

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Enemy
import com.lifeops.app.game.run.Projectile
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-hit behaviour passives (DESIGN.md §9): pierce, ricochet, explosive. These change how a
 * shot behaves for *any* gun, so they're exercised at the engine's projectile-resolution layer with
 * hand-placed entities. The player's own gun is muted (a permanent reload) so the only friendly
 * projectile on the field is the crafted one under test — no auto-fire noise.
 */
class GunPassivesTest {

    private fun mutedEngine(): RunEngine =
        RunEngine(RunConfig(StartingWeapon.GATLING, levelCap = 20, maxHits = 999, startingGold = 0, seed = 1L, waves = 7))
            .also { it.player.reloadRemaining = 999f } // hold the player's own fire for the whole test

    private fun enemy(id: Int, pos: Vec2) =
        Enemy(id = id, type = EnemyType.SHAMBLER, pos = pos, health = 100_000f, maxHealth = 100_000f, moveSpeed = 0f)

    @Test
    fun penetrationCarriesAShotThroughMultipleEnemies() {
        val e = mutedEngine()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear()
        e.enemies.add(enemy(90001, Vec2(px + 40f, py)))
        e.enemies.add(enemy(90002, Vec2(px + 80f, py)))
        // A pierce-1 shot travelling straight through both bodies.
        val shot = Projectile(
            id = 90100, ownerId = RunEngine.PLAYER_ID, pos = Vec2(px + 20f, py), vel = Vec2(360f, 0f),
            damage = 50f, crit = false, lifeRemaining = 5f, friendly = true, pierce = 1,
        )
        e.projectiles.add(shot)
        repeat(40) { e.step(1f / 60f, RunInput()) }
        assertTrue("a pierce shot should strike both enemies in its path",
            shot.hitIds.containsAll(setOf(90001, 90002)))
    }

    @Test
    fun ricochetRedirectsAShotToAnOffAxisEnemy() {
        val e = mutedEngine()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear()
        e.enemies.add(enemy(91001, Vec2(px + 40f, py)))        // straight ahead — the first hit
        e.enemies.add(enemy(91002, Vec2(px + 40f, py + 60f)))  // off the straight path — only a bounce reaches it
        val shot = Projectile(
            id = 91100, ownerId = RunEngine.PLAYER_ID, pos = Vec2(px + 20f, py), vel = Vec2(360f, 0f),
            damage = 50f, crit = false, lifeRemaining = 5f, friendly = true, bouncesLeft = 1,
        )
        e.projectiles.add(shot)
        repeat(60) { e.step(1f / 60f, RunInput()) }
        assertTrue("a ricochet should bounce from the first enemy to the off-axis one",
            shot.hitIds.containsAll(setOf(91001, 91002)))
    }

    /**
     * A bounce spawns a *fresh* shot rather than turning the spent one around, so the shot that
     * leaves the first target still has its full pierce budget: pierce-1 + ricochet-1 is two
     * bodies on the way out and two more on the bounced leg, not two plus one.
     */
    @Test
    fun aRicochetCarriesTheFullPierceBudgetIntoItsNewLeg() {
        val e = mutedEngine()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear()
        e.enemies.add(enemy(93001, Vec2(px + 40f, py)))         // first leg: straight ahead
        e.enemies.add(enemy(93002, Vec2(px + 80f, py)))         // first leg: pierced through
        e.enemies.add(enemy(93003, Vec2(px + 80f, py + 60f)))   // the bounce target
        e.enemies.add(enemy(93004, Vec2(px + 80f, py + 120f)))  // only a pierced bounce reaches this one
        val shot = Projectile(
            id = 93100, ownerId = RunEngine.PLAYER_ID, pos = Vec2(px + 20f, py), vel = Vec2(360f, 0f),
            damage = 50f, crit = false, lifeRemaining = 5f, friendly = true, pierce = 1, bouncesLeft = 1,
        )
        e.projectiles.add(shot)
        repeat(120) { e.step(1f / 60f, RunInput()) }
        // The bounce chain shares one hit set, so the original shot's set records the whole chain.
        assertTrue("pierce should still apply after a ricochet — all four enemies hit, got ${shot.hitIds}",
            shot.hitIds.containsAll(setOf(93001, 93002, 93003, 93004)))
    }

    @Test
    fun explosiveRoundsSplashNearbyEnemies() {
        val e = mutedEngine()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear()
        val direct = enemy(92001, Vec2(px + 40f, py))
        val bystander = enemy(92002, Vec2(px + 62f, py)) // not the target, but inside the blast
        e.enemies.add(direct); e.enemies.add(bystander)
        val shot = Projectile(
            id = 92100, ownerId = RunEngine.PLAYER_ID, pos = Vec2(px + 20f, py), vel = Vec2(360f, 0f),
            damage = 100f, crit = false, lifeRemaining = 5f, friendly = true, explosionRadius = 50f,
        )
        e.projectiles.add(shot)
        repeat(20) { e.step(1f / 60f, RunInput()) }
        assertTrue("the direct hit should damage its target", direct.health < direct.maxHealth)
        // The player's gun is muted, so the only thing that can have hurt the bystander is the blast.
        assertTrue("an explosive round should splash a nearby non-target enemy", bystander.health < bystander.maxHealth)
    }
}
