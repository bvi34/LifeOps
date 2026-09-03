package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing on the docket that goes stale while the vehicle sits still.
 *
 * Everything else here is owed because your vehicle changed — miles went on it, a policy ran out. A
 * recall list is owed because *the answer* changed, which is why it needs a shelf life and a prompt
 * of its own rather than a date somebody is expected to remember.
 */
class RecallChecksTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `never checked is stale, which is the case that matters most`() {
        // A vehicle nobody has ever asked about is exactly the one carrying old open campaigns.
        assertTrue(RecallChecks.isStale(null, now))
    }

    @Test
    fun `an answer ages out on its cadence and not before`() {
        assertFalse(RecallChecks.isStale(now - 1 * day, now))
        assertFalse(RecallChecks.isStale(now - (RecallChecks.EVERY_DAYS - 1) * day, now))
        assertTrue(RecallChecks.isStale(now - RecallChecks.EVERY_DAYS * day, now))
        assertTrue(RecallChecks.isStale(now - 2 * RecallChecks.EVERY_DAYS * day, now))
    }

    @Test
    fun `every vehicle schedule carries the check, on that same cadence`() {
        // It is in the packs rather than invented behind somebody's back when a vehicle is added —
        // and in *both*, because no owner's manual tells you to ask NHTSA anything.
        SchedulePacks.vehiclePacks.forEach { pack ->
            val item = pack.items.singleOrNull { it.kind == PlanKind.RECALL_CHECK }
            assertTrue("${pack.id} has no recall check", item != null)
            assertEquals(RecallChecks.EVERY_DAYS, item!!.everyDays)
            assertEquals(null, item.everyMeter)
        }
    }

    @Test
    fun `a check is a prompt, not work - only upkeep writes a service record`() {
        assertTrue(PlanKind.UPKEEP.isWork)
        assertFalse(PlanKind.RECALL_CHECK.isWork)
        assertFalse(PlanKind.METER_READING.isWork)
    }

    @Test
    fun `the kind survives a round trip, and an unknown one opens as ordinary upkeep`() {
        // Rows written by an older build carry no kind at all; they were ordinary upkeep, and that
        // is what they must still open as.
        assertEquals(PlanKind.RECALL_CHECK, PlanKind.of("recall_check"))
        assertEquals(PlanKind.UPKEEP, PlanKind.of(null))
        assertEquals(PlanKind.UPKEEP, PlanKind.of("something_a_later_build_invented"))
    }

    @Test
    fun `applying a pack brings the check with it, as a plan that publishes to the week`() {
        val pack = SchedulePacks.GENERIC_VEHICLE
        val application = SchedulePlans.plan(pack, emptyList())
        val item = application.toCreate.singleOrNull { it.kind == PlanKind.RECALL_CHECK }
        assertTrue("the pack applied without a recall check", item != null)

        val plan = SchedulePlans.toPlan(item!!, pack, "asset-1", "plan-1", now)
        assertEquals(PlanKind.RECALL_CHECK, plan.kind)
        assertEquals(RecallChecks.EVERY_DAYS, plan.everyDays)
        assertTrue("the check has to reach the week to be worth having", plan.publishToLifeOps)

        // And a second apply leaves the one that is already there alone.
        val again = SchedulePlans.plan(pack, listOf(plan))
        assertTrue(again.toCreate.none { it.kind == PlanKind.RECALL_CHECK })
    }
}
