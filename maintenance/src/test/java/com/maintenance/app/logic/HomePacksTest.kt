package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which schedules a house is offered, and what is in them.
 *
 * The interesting half of this file is the *negative* assertions. A schedule that appears when it
 * should not is worse than one that does not appear: a house on mains drainage offered a septic
 * pumping every three years is an app that has to be argued with.
 */
class HomePacksTest {

    private fun home(
        zip: String? = null,
        yearBuilt: Int? = null,
        systems: Set<HomeSystem> = emptySet(),
        mortgage: Boolean = false
    ) = HomeFacts(
        zip = zip,
        climate = HomeLookup.climateOf(zip),
        yearBuilt = yearBuilt,
        systems = systems,
        hasMortgage = mortgage
    )

    private fun idsFor(facts: HomeFacts) = SchedulePacks.forHome(facts).map { it.id }

    @Test
    fun `a house nobody has said anything about still gets the standing lists`() {
        assertEquals(listOf("home-core", "home-ownership"), idsFor(home()))
    }

    @Test
    fun `the climate schedules follow the ZIP`() {
        assertTrue(idsFor(home(zip = "05401")).contains("home-cold"))
        assertTrue(idsFor(home(zip = "07001")).contains("home-cold"))

        val miami = idsFor(home(zip = "33101"))
        assertTrue(miami.contains("home-hot"))
        assertTrue(miami.contains("home-damp"))
        assertTrue("Miami does not want the winterising list", !miami.contains("home-cold"))

        val phoenix = idsFor(home(zip = "85001"))
        assertTrue(phoenix.contains("home-hot"))
        assertTrue("a desert is not a damp problem", !phoenix.contains("home-damp"))

        val seattle = idsFor(home(zip = "98101"))
        assertTrue(seattle.contains("home-damp"))
        assertTrue(!seattle.contains("home-hot"))
    }

    @Test
    fun `an unknown climate is offered no climate schedule rather than the wrong one`() {
        val ids = idsFor(home(zip = null))

        assertTrue(ids.none { it in setOf("home-cold", "home-hot", "home-damp") })
    }

    @Test
    fun `a system brings its own schedule and nothing else does`() {
        assertTrue(idsFor(home(systems = setOf(HomeSystem.SEPTIC))).contains("home-septic"))
        assertTrue(idsFor(home(systems = setOf(HomeSystem.WELL))).contains("home-well"))
        assertTrue(idsFor(home(systems = setOf(HomeSystem.FIREPLACE))).contains("home-fireplace"))
        assertTrue(idsFor(home(systems = setOf(HomeSystem.SUMP_PUMP))).contains("home-sump"))
        assertTrue(idsFor(home(systems = setOf(HomeSystem.IRRIGATION))).contains("home-irrigation"))
        assertTrue(idsFor(home(systems = setOf(HomeSystem.POOL))).contains("home-pool"))

        // And a house on mains drainage is never offered the septic list.
        assertTrue(idsFor(home(systems = setOf(HomeSystem.WELL))).none { it == "home-septic" })
    }

    @Test
    fun `the older-house schedule is offered on age and only on age`() {
        assertTrue(idsFor(home(yearBuilt = 1948)).contains("home-older"))
        assertTrue(idsFor(home(yearBuilt = 1979)).contains("home-older"))
        assertTrue(idsFor(home(yearBuilt = 1980)).none { it == "home-older" })
        assertTrue(idsFor(home(yearBuilt = 2016)).none { it == "home-older" })
        // A year nobody typed is not an old house.
        assertTrue(idsFor(home(yearBuilt = null)).none { it == "home-older" })
    }

    @Test
    fun `the mortgage schedule appears only when there is a loan against the house`() {
        assertTrue(idsFor(home(mortgage = true)).contains("home-mortgage"))
        assertTrue(idsFor(home(mortgage = false)).none { it == "home-mortgage" })
    }

    @Test
    fun `a real house is offered several, in the order they are worth doing`() {
        val ids = idsFor(
            home(zip = "05602", yearBuilt = 1948, systems = setOf(HomeSystem.SEPTIC, HomeSystem.FIREPLACE), mortgage = true)
        )

        assertEquals(
            listOf(
                "home-core", "home-cold", "home-older", "home-septic",
                "home-fireplace", "home-ownership", "home-mortgage"
            ),
            ids
        )
    }

    // --- the catalogue itself ---

    @Test
    fun `every home pack is coherent - unique keys, real intervals, nothing measured in miles`() {
        HomePacks.all.forEach { pack ->
            assertTrue(pack.label.isNotBlank())
            assertTrue(pack.source.isNotBlank())
            assertNull("a house has no Schedule B", pack.duty)
            assertTrue("${pack.id} claims to be checked", pack.provisional)
            assertTrue("${pack.id} has no items", pack.items.isNotEmpty())
            assertEquals(
                "${pack.id} repeats an item key",
                pack.items.size,
                pack.items.map { it.key }.toSet().size
            )
            pack.items.forEach { item ->
                assertTrue("${pack.id}/${item.key} has no title", item.title.isNotBlank())
                assertTrue(
                    "${pack.id}/${item.key} has no interval, so it would never come round",
                    (item.everyDays ?: 0) > 0
                )
                // A house wears no meter: an interval in miles could never fall due.
                assertNull("${pack.id}/${item.key} is measured in miles", item.everyMeter)
                assertTrue(item.atMeter.isEmpty())
                assertEquals(PlanKind.UPKEEP, item.kind)
            }
        }
    }

    @Test
    fun `pack ids are unique across the whole catalogue, because provenance is keyed on them`() {
        assertEquals(SchedulePacks.all.size, SchedulePacks.all.map { it.id }.toSet().size)
        HomePacks.all.forEach { pack ->
            assertEquals("byId lost ${pack.id}", pack, SchedulePacks.byId(pack.id))
        }
    }

    @Test
    fun `home packs and vehicle packs never appear in each other's lists`() {
        val vehicle = VehicleFacts(make = "Jeep", model = "Wrangler", year = 2018, displacementLitres = 3.6)

        assertTrue(SchedulePacks.forVehicle(vehicle).none { it.id.startsWith("home-") })
        assertTrue(
            SchedulePacks.forHome(home(zip = "05401", mortgage = true)).all { it.fit is PackFit.Home }
        )
        assertEquals(AssetKind.HOME, HomePacks.CORE.fit.kind)
        assertEquals(AssetKind.VEHICLE, SchedulePacks.GENERIC_VEHICLE.fit.kind)
    }

    @Test
    fun `a home pack summarises itself without a duty it does not have`() {
        assertEquals("${HomePacks.CORE.items.size} items", HomePacks.CORE.summary)
        assertEquals("${SchedulePacks.GENERIC_VEHICLE.items.size} items · Schedule A", SchedulePacks.GENERIC_VEHICLE.summary)
    }

    // --- applying them ---

    @Test
    fun `applying a home pack twice adds nothing the second time`() {
        val first = SchedulePlans.plan(HomePacks.CORE, emptyList())
        assertEquals(HomePacks.CORE.items.size, first.toCreate.size)

        val plans = first.toCreate.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, HomePacks.CORE, "asset-1", "plan-$index", NOW)
        }
        val again = SchedulePlans.plan(HomePacks.CORE, plans)

        assertTrue(again.isNoOp)
        assertEquals("Every item is already here", again.summary)
    }

    @Test
    fun `the septic schedule arriving six months late adds only itself`() {
        // The case the many-small-packs shape exists for: the house was typed in without the tank,
        // the core list was applied and tuned, and the tank is written down later.
        val core = SchedulePlans.plan(HomePacks.CORE, emptyList()).toCreate.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, HomePacks.CORE, "asset-1", "plan-$index", NOW)
        }

        val septic = SchedulePlans.plan(HomePacks.SEPTIC, core)

        assertEquals(HomePacks.SEPTIC.items.size, septic.toCreate.size)
        assertTrue(septic.alreadyThere.isEmpty())
    }

    @Test
    fun `an item becomes an ordinary plan that carries where it came from`() {
        val item = HomePacks.MORTGAGE.items.first { it.key == "pmi-check" }
        val plan = SchedulePlans.toPlan(item, HomePacks.MORTGAGE, "asset-1", "plan-1", NOW)

        assertEquals("home-mortgage", plan.sourcePack)
        assertEquals("pmi-check", plan.sourceItem)
        assertEquals(182, plan.everyDays)
        assertTrue(plan.active)
        assertNotNull(plan.notes)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
