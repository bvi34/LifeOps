package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePlansTest {

    private val now = 1_700_000_000_000L

    private fun facts(
        make: String? = "JEEP",
        model: String? = "Wrangler",
        year: Int? = 2018,
        litres: Double? = 3.6
    ) = VehicleFacts(make = make, model = model, year = year, displacementLitres = litres)

    private fun existing(title: String, pack: String? = null, item: String? = null) = UpkeepPlan(
        id = "p-$title",
        assetId = "asset-1",
        title = title,
        sourcePack = pack,
        sourceItem = item
    )

    @Test
    fun `a VIN's facts choose the pack, with the fallback always offered beneath it`() {
        val matches = SchedulePacks.forVehicle(facts())

        assertEquals(
            listOf("jeep-jl-36-a", "generic-vehicle"),
            matches.map { it.id }
        )
    }

    @Test
    fun `a vehicle nothing knows still gets the generic starting point`() {
        val matches = SchedulePacks.forVehicle(facts(make = "SAAB", model = "900", year = 1993, litres = 2.0))

        assertEquals(listOf("generic-vehicle"), matches.map { it.id })
    }

    @Test
    fun `the specific pack is not offered for the wrong engine, year or model`() {
        assertTrue(SchedulePacks.forVehicle(facts(litres = 2.0)).none { it.id == "jeep-jl-36-a" })
        assertTrue(SchedulePacks.forVehicle(facts(year = 2004)).none { it.id == "jeep-jl-36-a" })
        assertTrue(SchedulePacks.forVehicle(facts(model = "Cherokee")).none { it.id == "jeep-jl-36-a" })
        // …and a missing fact is not a match, rather than a maybe.
        assertTrue(SchedulePacks.forVehicle(facts(litres = null)).none { it.id == "jeep-jl-36-a" })
    }

    @Test
    fun `an engine reported as 3600000000001 litres still matches 3 point 6`() {
        assertTrue(SchedulePacks.forVehicle(facts(litres = 3.6000000000000001)).any { it.id == "jeep-jl-36-a" })
    }

    @Test
    fun `applying to a bare asset creates every item`() {
        val application = SchedulePlans.plan(SchedulePacks.JEEP_JL_36_NORMAL, emptyList())

        assertEquals(SchedulePacks.JEEP_JL_36_NORMAL.items.size, application.toCreate.size)
        assertTrue(application.alreadyThere.isEmpty())
        assertTrue(application.summary.endsWith("to add"))
    }

    @Test
    fun `applying twice adds nothing the second time`() {
        val pack = SchedulePacks.JEEP_JL_36_NORMAL
        val plans = pack.items.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, pack, "asset-1", "plan-$index", now)
        }

        val second = SchedulePlans.plan(pack, plans)

        assertTrue(second.isNoOp)
        assertEquals("Every item is already here", second.summary)
    }

    @Test
    fun `a plan you already typed by hand is adopted rather than duplicated`() {
        val mine = existing("Engine oil & filter")

        val application = SchedulePlans.plan(SchedulePacks.GENERIC_VEHICLE, listOf(mine))

        assertTrue(application.toCreate.none { it.title == "Engine oil & filter" })
        assertEquals(listOf("Engine oil & filter"), application.alreadyThere.map { it.second.title })
    }

    @Test
    fun `a renamed pack plan is still recognised by where it came from`() {
        val renamed = existing("Oil (Quick Lube on 5th)", pack = "generic-vehicle", item = "engine-oil")

        val application = SchedulePlans.plan(SchedulePacks.GENERIC_VEHICLE, listOf(renamed))

        assertTrue(application.toCreate.none { it.key == "engine-oil" })
    }

    @Test
    fun `an item becomes a plan carrying its own provenance and its own clock`() {
        val item = SchedulePacks.JEEP_JL_36_NORMAL.items.first { it.key == "spark-plugs" }

        val plan = SchedulePlans.toPlan(item, SchedulePacks.JEEP_JL_36_NORMAL, "asset-1", "plan-1", now)

        assertEquals("jeep-jl-36-a", plan.sourcePack)
        assertEquals("spark-plugs", plan.sourceItem)
        assertEquals(listOf(100_000L, 200_000L), plan.atMeter)
        assertEquals(now, plan.createdAt)
        assertEquals(PlanKind.UPKEEP, plan.kind)
    }

    @Test
    fun `every shipped pack is coherent`() {
        SchedulePacks.all.forEach { pack ->
            assertTrue("${pack.id} has no items", pack.items.isNotEmpty())
            assertEquals(
                "${pack.id} repeats an item key",
                pack.items.size,
                pack.items.map { it.key }.toSet().size
            )
            assertTrue("${pack.id} cites no source", pack.source.isNotBlank())
            pack.items.forEach { item ->
                assertTrue("${pack.id}/${item.key} is unscheduled", item.everyDays != null || item.everyMeter != null || item.atMeter.isNotEmpty())
                assertEquals("${pack.id}/${item.key} milestones out of order", item.atMeter.sorted(), item.atMeter)
            }
            // Every vehicle pack asks for the reading everything else is measured against.
            assertTrue(
                "${pack.id} has no odometer prompt",
                pack.items.any { it.kind == PlanKind.METER_READING }
            )
        }
        assertEquals(SchedulePacks.all.size, SchedulePacks.all.map { it.id }.toSet().size)
    }
}
