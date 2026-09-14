package com.lifeops.app.game

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StoreCatalog
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.run.Enemy
import com.lifeops.app.game.run.Mine
import com.lifeops.app.game.run.RunConfig
import com.lifeops.app.game.run.RunEngine
import com.lifeops.app.game.run.RunInput
import com.lifeops.app.game.run.RunStatus
import com.lifeops.app.game.run.applyStorePurchase
import com.lifeops.app.game.run.chooseOverflow
import com.lifeops.app.game.run.chooseSetBonus
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Mines equipment (DESIGN.md §9): sown near the player, detonating on proximity for a blast. */
class MinesTest {

    private val allIds = StoreCatalog.ITEMS.map { it.id }.toSet()

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
            e.step(1f / 60f, RunInput(Vec2(cos(ang), sin(ang))))
        }
        error("the run never reached the store")
    }

    @Test
    fun owningMinesSowsThemNearThePlayer() {
        val e = driveToStore(allIds - "mines")
        assertEquals("mines", e.snapshot().storeOptions.single().itemId)
        e.applyStorePurchase("mines") // rank 1 → MINE_COUNT 1
        var deployed = false
        repeat(120) {
            e.step(1f / 60f, RunInput())
            if (e.mines.isNotEmpty()) deployed = true
        }
        assertTrue("owning Mines should sow at least one mine", deployed)
    }

    @Test
    fun aMineDetonatesOnContactAndSplashesNeighbours() {
        // Mute the player's gun so the only thing that can damage the enemies is the mine's blast.
        val e = RunEngine(RunConfig(StartingWeapon.GATLING, levelCap = 20, maxHits = 999, startingGold = 0, seed = 1L, waves = 7))
            .also { it.player.reloadRemaining = 999f }
        val mpos = Vec2(e.player.pos.x + 100f, e.player.pos.y)
        e.enemies.clear()
        val stepped = Enemy(id = 95001, type = EnemyType.SHAMBLER, pos = mpos, health = 100_000f, maxHealth = 100_000f, moveSpeed = 0f)
        val neighbour = Enemy(id = 95002, type = EnemyType.SHAMBLER, pos = Vec2(mpos.x + 30f, mpos.y), health = 100_000f, maxHealth = 100_000f, moveSpeed = 0f)
        e.enemies.add(stepped); e.enemies.add(neighbour)
        e.mines.add(Mine(id = 95100, pos = mpos, arming = 0f)) // already armed

        e.step(1f / 60f, RunInput())

        assertTrue("the mine detonates on the enemy that stepped on it", stepped.health < stepped.maxHealth)
        assertTrue("the blast splashes a nearby enemy", neighbour.health < neighbour.maxHealth)
        assertTrue("the mine is consumed on detonation", e.mines.isEmpty())
    }
}
