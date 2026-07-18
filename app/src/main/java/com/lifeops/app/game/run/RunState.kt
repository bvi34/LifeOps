package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.core.Modifier
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.Vec2
import com.lifeops.app.game.map.GameMap

enum class RunStatus { RUNNING, LEVEL_UP, VICTORY, DEFEAT }

/** One offered pick on level-up (DESIGN.md §6). Upgrades a held artifact or grants a new one. */
data class LevelUpOption(
    val modifier: Modifier,
    val resultingRank: Int,
    val isNew: Boolean,
) {
    val label: String get() = if (isNew) "New · ${modifier.name}" else "${modifier.name} → rank $resultingRank"
}

/** Immutable per-frame view handed to the renderer. Cheap value types only — no engine internals. */
data class RunSnapshot(
    val arena: Vec2,
    val playerPos: Vec2,
    val playerHealth: Float,
    val playerMaxHealth: Float,
    /** Unit aim direction, for drawing the barrel/reticle. */
    val playerAim: Vec2,
    /** 0 = idle, 1 = just fired; renderer draws a muzzle flash. */
    val playerMuzzleFrac: Float,
    /** 0 = unharmed, 1 = just took touch damage; renderer flashes the player red. */
    val playerHurtFrac: Float,
    val level: Int,
    val levelCap: Int,
    val xp: Float,
    val xpToNext: Float,
    val gold: Int,
    val wave: Int,
    val totalWaves: Int,
    val score: Long,
    val status: RunStatus,
    val strained: Boolean,
    val enemies: List<EnemyView>,
    val projectiles: List<Vec2>,
    val pickups: List<PickupView>,
    val effects: List<EffectView>,
    val boss: BossView?,
    val levelUpOptions: List<LevelUpOption>,
    val weapon: StartingWeapon,
    val weaponName: String,
    val challengeModeName: String,
    val held: List<HeldView>,
    /** Static map geometry (immutable reference — the renderer draws tiles from it). */
    val map: GameMap,
    val unlockedZoneIds: Set<Int>,
    val openBarrierIds: Set<Int>,
    /** A locked door the player is standing next to, or null; drives the buy prompt. */
    val nearbyBarrier: BarrierPrompt?,
)

data class BarrierPrompt(val id: Int, val name: String, val cost: Int, val affordable: Boolean)

data class EnemyView(
    val pos: Vec2,
    val radius: Float,
    val healthFrac: Float,
    val type: EnemyType,
    /** 0 = idle, 1 = just hit; renderer flashes the silhouette toward white. */
    val hitFlashFrac: Float,
    /** Direction the enemy is heading (toward the player), for orienting its silhouette. */
    val facing: Vec2,
)
data class PickupView(val pos: Vec2, val kind: PickupKind)
data class EffectView(val pos: Vec2, val kind: EffectKind, val ageFrac: Float, val worldRadius: Float)
data class BossView(val healthFrac: Float, val name: String)
data class HeldView(val name: String, val rank: Int, val maxRank: Int)

/** Player intent for a frame. [move] is a raw joystick vector (clamped to unit length by the engine). */
data class RunInput(
    val move: Vec2 = Vec2.ZERO,
    /** Optional manual aim override; when non-zero the aimed weapon fires along it (DESIGN.md §4). */
    val aimOverride: Vec2? = null,
)
