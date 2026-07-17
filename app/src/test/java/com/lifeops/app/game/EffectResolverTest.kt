package com.lifeops.app.game

import com.lifeops.app.game.core.EffectResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectResolverTest {

    @Test
    fun budgetCapsEffectsPerFrameAndFlagsStrain() {
        val r = EffectResolver(perFrameBudget = 3, maxDepth = 8)
        r.beginFrame()
        var ran = 0
        repeat(5) { if (r.resolve { ran++ }) Unit }
        assertEquals(3, ran)
        assertEquals(3, r.spent)
        assertTrue(r.strained)
    }

    @Test
    fun beginFrameResetsBudgetAndStrain() {
        val r = EffectResolver(perFrameBudget = 1, maxDepth = 8)
        r.beginFrame()
        r.resolve { }
        assertFalse(r.resolve { }) // over budget
        assertTrue(r.strained)
        r.beginFrame()
        assertFalse(r.strained)
        assertTrue(r.resolve { })
    }

    @Test
    fun recursionDepthIsCapped() {
        val r = EffectResolver(perFrameBudget = 1000, maxDepth = 4)
        r.beginFrame()
        var maxObservedDepth = 0
        var depth = 0
        fun recurse() {
            val ok = r.resolve {
                depth++
                maxObservedDepth = maxOf(maxObservedDepth, depth)
                recurse()
                depth--
            }
            if (!ok) { /* throttled at the cap */ }
        }
        recurse()
        assertEquals(4, maxObservedDepth)
        assertTrue(r.strained)
    }
}
