package com.lifeops.app.game

import com.lifeops.app.game.core.Op
import com.lifeops.app.game.core.Scope
import com.lifeops.app.game.core.Stat
import com.lifeops.app.game.core.StatBlock
import com.lifeops.app.game.core.StatContribution
import org.junit.Assert.assertEquals
import org.junit.Test

class StatBlockTest {

    private fun block() = StatBlock(mapOf(Stat.DAMAGE to 100f, Stat.PROJECTILES to 1f))

    @Test
    fun additivePercentsStackLinearly() {
        // Four +10% ranks read as +40%, not compounding (DESIGN.md §5).
        val b = block()
        repeat(4) { b.add(StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 0.10f)) }
        assertEquals(140f, b.resolve(Stat.DAMAGE, Scope.AIMED), 0.001f)
    }

    @Test
    fun flatAddsAfterPercent() {
        val b = block()
        b.add(StatContribution(Stat.PROJECTILES, Scope.AIMED, Op.FLAT, 1f))
        b.add(StatContribution(Stat.PROJECTILES, Scope.AIMED, Op.FLAT, 1f))
        assertEquals(3f, b.resolve(Stat.PROJECTILES, Scope.AIMED), 0.001f)
    }

    @Test
    fun aimedAndAutoScopesDoNotBleed() {
        val b = block()
        b.add(StatContribution(Stat.DAMAGE, Scope.AIMED, Op.ADD_PERCENT, 1.0f)) // +100% aimed only
        assertEquals(200f, b.resolve(Stat.DAMAGE, Scope.AIMED), 0.001f)
        assertEquals(100f, b.resolve(Stat.DAMAGE, Scope.AUTO), 0.001f)
    }

    @Test
    fun globalAppliesToEveryScope() {
        val b = block()
        b.add(StatContribution(Stat.DAMAGE, Scope.GLOBAL, Op.ADD_PERCENT, 0.5f))
        assertEquals(150f, b.resolve(Stat.DAMAGE, Scope.AIMED), 0.001f)
        assertEquals(150f, b.resolve(Stat.DAMAGE, Scope.AUTO), 0.001f)
    }

    @Test
    fun defaultBaseUsedWhenUnset() {
        val b = StatBlock()
        assertEquals(StatBlock.DEFAULT_BASE[Stat.MOVE_SPEED], b.resolve(Stat.MOVE_SPEED))
    }
}
