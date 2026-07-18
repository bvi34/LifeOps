package com.lifeops.app.game

import com.lifeops.app.game.core.EntityKind
import com.lifeops.app.game.core.EventBus
import com.lifeops.app.game.core.GameEvent
import com.lifeops.app.game.core.GameListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventBusTest {

    @Test
    fun emitReachesSubscribers() {
        val bus = EventBus()
        var hits = 0
        bus.subscribe { _, _ -> hits++ }
        bus.emit(GameEvent.OnKill(0, 1, EntityKind.ENEMY))
        assertEquals(1, hits)
    }

    @Test
    fun unsubscribeStopsDelivery() {
        val bus = EventBus()
        var hits = 0
        val l: GameListener = bus.subscribe { _, _ -> hits++ }
        bus.emit(GameEvent.OnHeal(0, 5f))
        bus.unsubscribe(l)
        bus.emit(GameEvent.OnHeal(0, 5f))
        assertEquals(1, hits)
    }

    @Test
    fun listenerMayEmitFollowUpWithoutConcurrentModification() {
        val bus = EventBus()
        val seen = ArrayList<String>()
        bus.subscribe { event, b ->
            if (event is GameEvent.OnHit) {
                seen.add("hit")
                b.emit(GameEvent.OnKill(event.sourceId, event.targetId, EntityKind.ENEMY))
            } else if (event is GameEvent.OnKill) {
                seen.add("kill")
            }
        }
        bus.emit(GameEvent.OnHit(0, 1, 10f, false))
        assertTrue(seen.contains("hit"))
        assertTrue(seen.contains("kill"))
    }
}
