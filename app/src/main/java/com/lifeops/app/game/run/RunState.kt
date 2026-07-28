package com.lifeops.app.game.run

import com.lifeops.app.game.content.EnemyType
import com.lifeops.app.game.content.SetBonuses
import com.lifeops.app.game.content.TempBoosts
import com.lifeops.app.game.content.StartingWeapon
import com.lifeops.app.game.content.StructureType
import com.lifeops.app.game.core.Modifier
import com.lifeops.app.game.core.PickupKind
import com.lifeops.app.game.core.Vec2

/**
 * - [SET_BONUS] pauses at each set boundary for the boon/bane draft (DESIGN.md §7).
 * - [OVERFLOW] pauses at cap for the gold/heal/temp-boost micro-pick (DESIGN.md §6).
 */
enum class RunStatus { RUNNING, LEVEL_UP, SET_BONUS, STORE, OVERFLOW, VICTORY, DEFEAT }

/** One offered pick on level-up (DESIGN.md §6). Upgrades a held artifact or grants a new one. */
data class LevelUpOption(
    val modifier: Modifier,
    val resultingRank: Int,
    val isNew: Boolean,
    /** For a combat-equipment rank past the first: the random upgrade this pick rolls (§5/§9). */
    val equipmentUpgrade: com.lifeops.app.game.content.EquipmentUpgrades.Upgrade? = null,
) {
    val label: String get() = when {
        isNew -> "New · ${modifier.name}"
        equipmentUpgrade != null -> "${modifier.name} → rank $resultingRank · ${equipmentUpgrade.label}"
        else -> "${modifier.name} → rank $resultingRank"
    }
}

/**
 * One paired offer in the per-set draft (DESIGN.md §7): a player [boon] and the [bane] that rides
 * along with it. Choosing it powers the player up *and* the enemies — you cannot take the boon
 * without the bane, which is what keeps the endless loop escalating in lockstep.
 */
data class SetBonusOption(
    val boon: SetBonuses.Boon,
    val bane: SetBonuses.Bane,
)

/**
 * One offer in the between-set draft shop (DESIGN.md §9). A flattened view of a
 * [com.lifeops.app.game.content.StoreCatalog.Item] — enough for the overlay to render and price it.
 * Buying spends banked Modifier Budget (brokered by the ViewModel, never the pure engine).
 */
data class StoreOffer(
    val itemId: String,
    val name: String,
    val description: String,
    /** Tier label ("Passive", "Gun", …) for the badge. */
    val categoryLabel: String,
    /** Banked Modifier-Budget price. */
    val cost: Int,
)

/** What an [OVERFLOW] micro-pick grants (DESIGN.md §6). All three are strictly in-run. */
enum class OverflowKind { GOLD, HEAL, TEMP_BOOST }

/** One of the 2–3 instant effects offered on an overflow fill past the level cap. */
data class OverflowOption(
    val kind: OverflowKind,
    val label: String,
    val description: String,
    /** Gold added (GOLD) or hearts restored (HEAL). */
    val amount: Int = 0,
    /** The temporary surge granted (TEMP_BOOST only). */
    val surge: TempBoosts.Surge? = null,
)

/** Immutable per-frame view handed to the renderer. Cheap value types only — no engine internals. */
data class RunSnapshot(
    /** Fixed maximum world extent (for a stable camera). */
    val worldSize: Vec2,
    /** Top-left / bottom-right of the currently-active (lit) region; the rest is dead margin. */
    val activeMin: Vec2,
    val activeMax: Vec2,
    val cellSize: Float,
    val playerPos: Vec2,
    /** Discrete hearts remaining / max. */
    val playerHits: Int,
    val playerMaxHits: Int,
    /** True during post-hit invulnerability frames (renderer blinks the player). */
    val playerInvuln: Boolean,
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
    /** Rounds left in the magazine / its capacity (DESIGN.md §4). */
    val ammo: Int,
    val magazine: Int,
    /** True while a reload is in progress (weapon offline). */
    val reloading: Boolean,
    /** Reload progress 0→1 while [reloading]; 0 otherwise. */
    val reloadFrac: Float,
    /** True if the weapon spins up (Gatling). */
    val spinUp: Boolean,
    /** Spin-up progress 0→1 (floor→ceiling fire rate) for a spin-up weapon; 0 otherwise. */
    val spinFrac: Float,
    val wave: Int,
    val totalWaves: Int,
    /** Endless-mode loop/difficulty tier (0 = first loop). */
    val tier: Int,
    /** Set ceiling for a bounded run (the dev run auto-ends at this many sets); null = endless. */
    val maxSets: Int? = null,
    /** True for the weekly dev/sandbox run: unlimited resources, nothing earned in it is permanent. */
    val devRun: Boolean = false,
    val score: Long,
    val status: RunStatus,
    /** Times the player bought back into this run after a defeat (DESIGN.md §7). */
    val revives: Int,
    val strained: Boolean,
    val enemies: List<EnemyView>,
    val projectiles: List<Vec2>,
    val enemyProjectiles: List<Vec2>,
    val structures: List<StructureView>,
    val structureCosts: Map<StructureType, Int>,
    /** Sown proximity mines (the Mines equipment, §9). */
    val mines: List<MineView>,
    val pickups: List<PickupView>,
    val effects: List<EffectView>,
    val boss: BossView?,
    val levelUpOptions: List<LevelUpOption>,
    /** The per-set boon/bane draft offers, shown while [status] is [RunStatus.SET_BONUS]. */
    val setBonusOptions: List<SetBonusOption>,
    /** The between-set store offers (up to 4), shown while [status] is [RunStatus.STORE] (§9). */
    val storeOptions: List<StoreOffer>,
    /** The overflow micro-pick offers, shown while [status] is [RunStatus.OVERFLOW]. */
    val overflowOptions: List<OverflowOption>,
    val weapon: StartingWeapon,
    val weaponName: String,
    val challengeModeName: String,
    val held: List<HeldView>,
    /** Player boons drafted so far this run (set draft), for the HUD. */
    val boons: List<String>,
    /** Currently-active temporary surges (label + seconds left), for the HUD. */
    val tempBuffs: List<TempBuffView>,
    /** The arena-expansion offer, or null at max size; drives the Expand button. */
    val expand: ExpandPrompt?,
)

data class TempBuffView(val label: String, val secondsLeft: Float)

data class ExpandPrompt(val cost: Int, val affordable: Boolean, val stage: Int, val maxStage: Int)

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
data class StructureView(
    val pos: Vec2,
    val type: StructureType,
    val healthFrac: Float,
    val aim: Vec2,
    /** True for engine-deployed auto-turrets; false for player-placed defenses. */
    val artifactTurret: Boolean = false,
    /** Auto-turret life remaining as a fraction of its lifespan (1 for permanent structures). */
    val ttlFrac: Float = 1f,
)
data class EffectView(val pos: Vec2, val kind: EffectKind, val ageFrac: Float, val worldRadius: Float)
data class MineView(val pos: Vec2, val armed: Boolean, val triggerRadius: Float)
data class BossView(val healthFrac: Float, val name: String)
data class HeldView(val name: String, val rank: Int, val maxRank: Int)

/** Player intent for a frame. [move] is a raw joystick vector (clamped to unit length by the engine). */
data class RunInput(
    val move: Vec2 = Vec2.ZERO,
    /** Optional manual aim override; when non-zero the aimed weapon fires along it (DESIGN.md §4). */
    val aimOverride: Vec2? = null,
)
