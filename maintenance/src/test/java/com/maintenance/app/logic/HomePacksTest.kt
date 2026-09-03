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
        region: Region? = null,
        structure: HomeStructure? = null,
        yearBuilt: Int? = null,
        features: Set<HomeFeature> = emptySet(),
        mortgage: Boolean = false
    ) = HomeFacts(
        zip = zip,
        region = region ?: HomeLookup.regionOf(zip),
        structure = structure,
        yearBuilt = yearBuilt,
        features = features,
        hasMortgage = mortgage
    )

    private fun idsFor(facts: HomeFacts) = SchedulePacks.forHome(facts).map { it.id }

    @Test
    fun `a house nobody has said anything about still gets the standing lists`() {
        // Including the outside of the building: an unpicked type of home is not a condo, and
        // silence should cost a household the schedule that is usually wrong, not the usual one.
        assertEquals(listOf("home-core", "home-envelope", "home-ownership"), idsFor(home()))
    }

    // --- the type of home, which is what a building structurally owes ---

    @Test
    fun `a manufactured home gets the list a site-built one has never heard of`() {
        val ids = idsFor(home(structure = HomeStructure.MANUFACTURED))

        assertTrue(ids.contains("home-manufactured"))
        // It still owns its own outside.
        assertTrue(ids.contains("home-envelope"))

        listOf(HomeStructure.CONVENTIONAL, HomeStructure.TOWNHOUSE, HomeStructure.CONDO).forEach { other ->
            assertTrue(
                "$other was offered the manufactured list",
                idsFor(home(structure = other)).none { it == "home-manufactured" }
            )
        }
        // And a type nobody picked is not quietly treated as one: piers under a condo is nonsense.
        assertTrue(idsFor(home()).none { it == "home-manufactured" })
    }

    @Test
    fun `a condo owner is not told twice a year to go and clear their gutters`() {
        val condo = idsFor(home(structure = HomeStructure.CONDO))

        assertTrue(condo.none { it == "home-envelope" })
        // What is still theirs: the alarms, the filter, the water heater, the dryer vent.
        assertTrue(condo.contains("home-core"))
        assertTrue(condo.contains("home-ownership"))

        listOf(HomeStructure.CONVENTIONAL, HomeStructure.MANUFACTURED, HomeStructure.TOWNHOUSE).forEach { owns ->
            assertTrue("$owns lost the outside of its own building", idsFor(home(structure = owns)).contains("home-envelope"))
        }
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
    fun `an unknown region is offered no weather schedule rather than the wrong one`() {
        val ids = idsFor(home(zip = null))

        assertTrue(ids.none { it in setOf("home-cold", "home-hot", "home-damp") })
        assertTrue(
            ids.none { it in setOf("home-hurricane", "home-wildfire", "home-severe-storm", "home-earthquake") }
        )
    }

    // --- what the weather does at its worst ---

    @Test
    fun `every hazard a region can carry brings exactly one schedule`() {
        val expected = mapOf(
            Hazard.HURRICANE to "home-hurricane",
            Hazard.WILDFIRE to "home-wildfire",
            Hazard.SEVERE_STORM to "home-severe-storm",
            Hazard.EARTHQUAKE to "home-earthquake"
        )
        assertEquals("a hazard was added without a schedule", Hazard.entries.toSet(), expected.keys)

        expected.forEach { (hazard, packId) ->
            val carriers = Region.entries.filter { hazard in it.hazards }
            assertTrue("no region carries ${hazard.key}", carriers.isNotEmpty())
            carriers.forEach { region ->
                assertTrue(
                    "${region.key} did not bring $packId",
                    idsFor(home(region = region)).contains(packId)
                )
            }
            Region.entries.filter { hazard !in it.hazards }.forEach { region ->
                assertTrue(
                    "${region.key} was offered $packId",
                    idsFor(home(region = region)).none { it == packId }
                )
            }
        }
    }

    @Test
    fun `the hazards follow the ZIP the same way the climate does`() {
        assertTrue(idsFor(home(zip = "33101")).contains("home-hurricane"))   // Miami
        assertTrue(idsFor(home(zip = "85001")).contains("home-wildfire"))    // Phoenix
        assertTrue(idsFor(home(zip = "73101")).contains("home-severe-storm")) // Oklahoma City
        assertTrue(idsFor(home(zip = "94110")).contains("home-earthquake"))  // San Francisco

        // Vermont has a hard winter and none of the four, which is the ordinary case.
        val vermont = idsFor(home(zip = "05401"))
        assertTrue(vermont.contains("home-cold"))
        assertTrue(vermont.none { it.startsWith("home-hurricane") || it.startsWith("home-wildfire") })
    }

    @Test
    fun `a picked region overrules the ZIP all the way through to the schedules`() {
        // Truckee: the ZIP prefix says California, and the house is in the snow behind it.
        val guessed = idsFor(home(zip = "96161"))
        assertTrue(guessed.contains("home-earthquake"))
        assertTrue(guessed.none { it == "home-cold" })

        val picked = idsFor(home(zip = "96161", region = Region.MOUNTAIN_WEST))
        assertTrue(picked.contains("home-cold"))
        assertTrue(picked.contains("home-wildfire"))
        assertTrue(picked.none { it == "home-earthquake" })
    }

    @Test
    fun `the jobs two hazard packs agree on are not scheduled twice`() {
        // A gas house in earthquake country, and a hurricane house that already photographs its
        // rooms for the ownership pack: both are one job with two reasons, written under one title.
        val gas = SchedulePlans.plan(HomePacks.GAS, emptyList()).toCreate.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, HomePacks.GAS, "asset-1", "gas-$index", NOW)
        }
        val quake = SchedulePlans.plan(HomePacks.EARTHQUAKE, gas)
        assertEquals(
            "the shut-off was scheduled twice",
            HomePacks.EARTHQUAKE.items.size - 1,
            quake.toCreate.size
        )

        val owned = SchedulePlans.plan(HomePacks.OWNERSHIP, emptyList()).toCreate.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, HomePacks.OWNERSHIP, "asset-2", "own-$index", NOW)
        }
        val hurricane = SchedulePlans.plan(HomePacks.HURRICANE, owned)
        assertEquals(
            "the room photographs were scheduled twice",
            HomePacks.HURRICANE.items.size - 1,
            hurricane.toCreate.size
        )
    }

    // --- what the household announced it has ---

    @Test
    fun `every feature on the picker brings exactly one schedule, and only when ticked`() {
        // The promise the picker makes: tick a thing, its schedule appears. Nothing on that list is
        // decoration, and nothing appears for a house that did not tick it.
        val expected = mapOf(
            HomeFeature.SEPTIC to "home-septic",
            HomeFeature.WELL to "home-well",
            HomeFeature.FUEL_GAS to "home-gas",
            HomeFeature.ALL_ELECTRIC to "home-electric",
            HomeFeature.SOLAR to "home-solar",
            HomeFeature.AIR_CONDITIONING to "home-cooling",
            HomeFeature.FIREPLACE to "home-fireplace",
            HomeFeature.SUMP_PUMP to "home-sump",
            HomeFeature.IRRIGATION to "home-irrigation",
            HomeFeature.POOL to "home-pool",
            HomeFeature.DECK to "home-deck",
            HomeFeature.GENERATOR to "home-generator"
        )
        assertEquals("a feature was added without a schedule", HomeFeature.entries.toSet(), expected.keys)

        expected.forEach { (feature, packId) ->
            assertTrue(
                "${feature.key} did not bring $packId",
                idsFor(home(features = setOf(feature))).contains(packId)
            )
            // Nothing else on the picker drags it in.
            HomeFeature.entries.filter { it != feature }.forEach { other ->
                assertTrue(
                    "${other.key} was offered $packId",
                    idsFor(home(features = setOf(other))).none { it == packId }
                )
            }
            assertTrue("$packId was offered to a house that ticked nothing", idsFor(home()).none { it == packId })
        }
    }

    @Test
    fun `ducted cooling reaches a cold house that has it, without the hot-climate list`() {
        // A house in Vermont with central air: the climate never suggests one, the tick does.
        val vermont = idsFor(home(zip = "05401", features = setOf(HomeFeature.AIR_CONDITIONING)))

        assertTrue(vermont.contains("home-cooling"))
        assertTrue(vermont.contains("home-cold"))
        assertTrue(vermont.none { it == "home-hot" })
    }

    @Test
    fun `the two packs that both service the air conditioning do not add it twice`() {
        // Phoenix with central air ticked matches both. The titles are deliberately identical, so
        // the second apply adopts the plan the first made rather than adding one beside it.
        val hot = SchedulePlans.plan(HomePacks.HOT_SUMMERS, emptyList()).toCreate.mapIndexed { index, item ->
            SchedulePlans.toPlan(item, HomePacks.HOT_SUMMERS, "asset-1", "plan-$index", NOW)
        }

        val cooling = SchedulePlans.plan(HomePacks.COOLING, hot)

        assertTrue("the cooling pack duplicated the hot pack's jobs", cooling.isNoOp)
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
            home(
                zip = "05602",
                structure = HomeStructure.CONVENTIONAL,
                yearBuilt = 1948,
                features = setOf(HomeFeature.SEPTIC, HomeFeature.FUEL_GAS, HomeFeature.FIREPLACE),
                mortgage = true
            )
        )

        assertEquals(
            listOf(
                "home-core", "home-envelope", "home-cold", "home-older",
                "home-septic", "home-gas", "home-fireplace",
                "home-ownership", "home-mortgage"
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
