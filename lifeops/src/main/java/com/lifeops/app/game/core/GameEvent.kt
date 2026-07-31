package com.lifeops.app.game.core

/**
 * Every combat moment is an event on the bus (DESIGN.md invariant #2) — even in the baseline
 * game with zero listeners. Phase-2 modifiers hook these; the baseline emits them from day one
 * so no combat path has to be re-plumbed later. Entity ids are stable within a single run.
 */
sealed interface GameEvent {
    data class OnSpawn(val entityId: Int, val kind: EntityKind) : GameEvent
    data class OnProjectileSpawn(val projectileId: Int, val ownerId: Int) : GameEvent
    data class OnHit(val sourceId: Int, val targetId: Int, val damage: Float, val crit: Boolean) : GameEvent
    data class OnKill(val sourceId: Int, val targetId: Int, val kind: EntityKind) : GameEvent
    data class OnHeal(val entityId: Int, val amount: Float) : GameEvent
    data class OnPickup(val entityId: Int, val pickup: PickupKind) : GameEvent
    data class OnLevelUp(val newLevel: Int, val overflow: Boolean) : GameEvent
    data class OnWaveStart(val wave: Int) : GameEvent
    data class OnRunEnd(val victory: Boolean, val score: Long) : GameEvent
}

enum class EntityKind { PLAYER, ENEMY, TURRET, BOSS }

enum class PickupKind { XP, GOLD, HEALTH }

/** A subscriber. Return is ignored; listeners emit follow-up events via the bus, not by return. */
fun interface GameListener {
    fun onEvent(event: GameEvent, bus: EventBus)
}

/**
 * Synchronous pub/sub. Emitting inside a listener re-enters [emit]; the [EffectResolver] is what
 * bounds that recursion (DESIGN.md invariant #3) — the bus itself just dispatches. Listeners are
 * snapshotted per-emit so a listener can subscribe/unsubscribe without a concurrent-modification
 * crash mid-dispatch.
 */
class EventBus {
    private val listeners = ArrayList<GameListener>()

    fun subscribe(listener: GameListener): GameListener {
        listeners.add(listener)
        return listener
    }

    fun unsubscribe(listener: GameListener) {
        listeners.remove(listener)
    }

    fun clear() = listeners.clear()

    fun emit(event: GameEvent) {
        if (listeners.isEmpty()) return
        val snapshot = listeners.toList()
        for (l in snapshot) l.onEvent(event, this)
    }
}
