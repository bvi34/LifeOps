package com.utilities.app.messages.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size arithmetic, which decides whether a photograph arrives or vanishes.
 *
 * A message over the carrier's cap is not rejected with an error — it is accepted by the radio and
 * dropped somewhere in the network. So the interesting assertions are all about being *pessimistic*
 * in the right places: overhead counted, the budget split with a floor under it, and a refusal when
 * no amount of squeezing would produce something worth looking at.
 */
class MmsBudgetTest {

    @Test
    fun `the overhead is taken off before anything is shared out`() {
        val room = MmsBudget.headroom(maxBytes = 300 * 1024, textBytes = 1024)
        assertEquals(300 * 1024 - MmsBudget.OVERHEAD_BYTES - 1024, room)
    }

    @Test
    fun `a long message on a small cap can leave no room at all`() {
        // Real, on a carrier capped at 100KB with a message somebody pasted into.
        assertEquals(0, MmsBudget.headroom(maxBytes = 20 * 1024, textBytes = 64 * 1024))
    }

    @Test
    fun `the budget is shared evenly rather than proportionally`() {
        // Proportional sounds fairer and is worse: it hands the biggest share to whichever
        // photograph the camera happened to compress least, and adding a second picture would
        // silently degrade the first.
        val room = 120_000
        assertEquals(40_000, MmsBudget.perAttachment(room, 3))
        assertEquals(60_000, MmsBudget.perAttachment(room, 2))
        assertEquals(0, MmsBudget.perAttachment(room, 0))
    }

    @Test
    fun `a budget too small to be worth using is refused`() {
        // A photograph squeezed under 8KB is a mosaic. Telling somebody it will not fit is better
        // than sending one — they can then send two messages.
        assertFalse(MmsBudget.workable(MmsBudget.MINIMUM_USEFUL_BYTES - 1))
        assertTrue(MmsBudget.workable(MmsBudget.MINIMUM_USEFUL_BYTES))
        assertNull(MmsBudget.plan(maxBytes = 300 * 1024, textBytes = 0, attachments = 50))
        assertNull(MmsBudget.plan(maxBytes = 20 * 1024, textBytes = 64 * 1024, attachments = 1))
    }

    @Test
    fun `a single picture on a default cap gets most of it`() {
        val each = MmsBudget.plan(attachments = 1)!!
        assertTrue("$each", each > 280 * 1024)
        assertTrue(each <= MmsBudget.DEFAULT_MAX_BYTES)
    }

    @Test
    fun `nothing to attach means no plan rather than a plan for nothing`() {
        assertNull(MmsBudget.plan(attachments = 0))
    }

    @Test
    fun `fits counts the overhead the sender will add`() {
        assertTrue(MmsBudget.fits(100_000, maxBytes = 300 * 1024))
        assertFalse(MmsBudget.fits(300 * 1024, maxBytes = 300 * 1024))
        assertTrue(MmsBudget.fits(300 * 1024 - MmsBudget.OVERHEAD_BYTES, maxBytes = 300 * 1024))
    }

    @Test
    fun `a carrier that answers with nonsense gets the default instead`() {
        assertEquals(MmsBudget.DEFAULT_MAX_BYTES, MmsBudget.clampCarrierMax(0))
        assertEquals(MmsBudget.DEFAULT_MAX_BYTES, MmsBudget.clampCarrierMax(-1))
        assertEquals(MmsBudget.DEFAULT_MAX_BYTES, MmsBudget.clampCarrierMax(512))
        assertEquals(MmsBudget.MAX_PLAUSIBLE_BYTES, MmsBudget.clampCarrierMax(50 * 1024 * 1024))
        assertEquals(614_400, MmsBudget.clampCarrierMax(614_400))
    }

    @Test
    fun `the ladder scales before it degrades, and ends somewhere still worth sending`() {
        val ladder = MmsBudget.LADDER
        assertTrue(ladder.isNotEmpty())
        // Monotonic: each rung is no bigger than the one before it, on both axes taken together.
        ladder.zipWithNext { a, b ->
            assertTrue(
                "the ladder goes backwards at $a -> $b",
                b.scale < a.scale || (b.scale == a.scale && b.quality <= a.quality)
            )
        }
        assertEquals("the first rung sends the picture as it is", 1.0f, ladder.first().scale, 0f)
        assertTrue("the last rung is still a photograph", ladder.last().quality >= 40)
        assertTrue("the last rung is still legible", ladder.last().scale >= 0.15f)
        assertTrue(ladder.all { it.quality in 1..100 && it.scale > 0f && it.scale <= 1f })
    }
}
