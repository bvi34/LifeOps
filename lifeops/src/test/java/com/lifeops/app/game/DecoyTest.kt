package com.lifeops.app.game

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Enemy
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.Structure
import com.lifeops.app.game.run.applyStorePurchase
import com.lifeops.app.game.run.chooseOverflow
import com.lifeops.app.game.run.chooseSetBonus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Decoy equipment and its targeting layer (DESIGN.md §9): rushers hunt the nearest base before
 * the player, and a decoy hijacks aggro from enemies that have strayed beyond its lure range. Both
 * are observed by whether the targeted structure takes damage — a non-blocking structure only gets
 * hit when an enemy has *chosen* to attack it, never incidentally.
 */
class DecoyTest {

    private fun mutedEngine(startingGold: Int = 0): RunEngine =
        RunEngine(RunConfig(StartingWeapon.GATLING, levelCap = 20, maxHits = 999, startingGold = startingGold, seed = 1L, waves = 7))
            .also { it.player.reloadRemaining = 999f } // the player's own gun never confuses the result

    private fun decoyAt(pos: Vec2, hp: Float) =
        Structure(id = 90001, type = StructureType.DECOY, col = 0, row = 0, pos = pos, hp = hp, maxHp = hp)

    @Test
    fun rushersMakeForTheNearestBaseBeforeThePlayer() {
        val e = mutedEngine()
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear(); e.structures.clear()
        // A base nearer the rusher than the player is; the rusher should peel off to smash it.
        val base = decoyAt(Vec2(px - 80f, py), hp = 8f)
        e.structures.add(base)
        e.enemies.add(Enemy(id = 2, type = EnemyType.RUSHER, pos = Vec2(px - 140f, py),
            health = 100_000f, maxHealth = 100_000f, moveSpeed = 92f))
        repeat(240) { e.step(1f / 60f, RunInput()) }
        assertTrue("a rusher should attack the nearer base rather than run past it to the player",
            base.hp < base.maxHp)
    }

    @Test
    fun aDecoyLuresEnemiesBeyondItsRange() {
        // Grow the arena so there's room to sit an enemy well beyond the decoy's lure range (220).
        val e = mutedEngine(startingGold = 999)
        repeat(3) { e.buyExpansion() }
        val px = e.player.pos.x; val py = e.player.pos.y
        e.enemies.clear(); e.structures.clear()
        val decoy = decoyAt(Vec2(px, py + 300f), hp = 10f) // 300 from the player — past the lure range
        e.structures.add(decoy)
        // A distant enemy (340 from the player) should be pulled onto the decoy.
        e.enemies.add(Enemy(id = 2, type = EnemyType.SHAMBLER, pos = Vec2(px, py + 340f),
            health = 100_000f, maxHealth = 100_000f, moveSpeed = 60f))
        repeat(300) { e.step(1f / 60f, RunInput()) }
        assertTrue("a decoy should lure an enemy that has strayed beyond its range off the player",
            decoy.hp < decoy.maxHp)
    }

    @Test
    fun anEnemyStopsToSmashAnyStructureInItsPath() {
        // A non-blocking structure that the enemy is NOT targeting (it's chasing the player) still
        // stops the enemy and takes damage — no walking through it. Own a Decoy so one is planted
        // into the grid (occupancy), which the path check reads.
        val e = driveToStore(StoreCatalog.ITEMS.map { it.id }.toSet() - "decoy")
        e.applyStorePurchase("decoy")
        var planted: Structure? = null
        var f = 0
        while (planted == null && f < 300) {
            e.step(1f / 60f, RunInput())
            planted = e.structures.firstOrNull { it.type == StructureType.DECOY }
            f++
        }
        val decoy = requireNotNull(planted) { "the Decoy equipment should have planted a decoy" }
        e.player.reloadRemaining = 999f // mute the gun so only a path-attack can damage the decoy

        // Put a player-chasing enemy on the far side of the decoy, collinear, close enough to target
        // the player (inside the lure range) so it beelines straight through the decoy's cell.
        e.enemies.clear()
        val away = (decoy.pos - e.player.pos).normalized()
        e.enemies.add(Enemy(id = 99001, type = EnemyType.SHAMBLER, pos = decoy.pos + away * 26f,
            health = 100_000f, maxHealth = 100_000f, moveSpeed = 60f))
        val hpBefore = decoy.hp
        repeat(150) { e.step(1f / 60f, RunInput()) }
        assertTrue("an enemy chasing the player still smashes a structure sitting in its path",
            decoy.hp < hpBefore)
    }

    private fun driveToStore(unlockedIds: Set<String>): RunEngine {
        val e = RunEngine(RunConfig(StartingWeapon.GATLING, levelCap = 30, maxHits = 999, startingGold = 0, seed = 5L, waves = 1,
            unlockedIds = unlockedIds))
        repeat(12000) { i ->
            when (e.status) {
                RunStatus.STORE -> return e
                RunStatus.LEVEL_UP -> e.snapshot().levelUpOptions.firstOrNull()?.let { e.choose(it) }
                RunStatus.SET_BONUS -> e.snapshot().setBonusOptions.firstOrNull()?.let { e.chooseSetBonus(it) }
                RunStatus.OVERFLOW -> e.snapshot().overflowOptions.firstOrNull()?.let { e.chooseOverflow(it) }
                else -> {}
            }
            val ang = i * 0.05f
            e.step(1f / 60f, RunInput(Vec2(kotlin.math.cos(ang), kotlin.math.sin(ang))))
        }
        error("the run never reached the store")
    }
}
