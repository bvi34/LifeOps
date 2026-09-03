package com.maintenance.app.logic

/**
 * A manufacturer's maintenance schedule, as data.
 *
 * ### Why the schedule is shipped rather than fetched
 *
 * The obvious pipeline is VIN → decoder → *the manufacturer's own service schedule* → plans. The
 * middle step does not exist: there is no public Mopar (or Toyota, or Ford) API for maintenance
 * intervals. The schedules live in owner's manuals, and the owner sites that hold them are behind
 * logins. Scraping one would mean OEM credentials inside an offline household app and a parser that
 * breaks the week they redesign — a worse app that is also more likely to be wrong.
 *
 * So a pack is **transcribed once, by hand, from the manual**, and lives here as Kotlin: versioned
 * in git, reviewable in a diff, unit-tested, and offline. The decode's job is not to fetch a
 * schedule but to *choose* one. Adding a vehicle later is authoring — one entry in
 * [SchedulePacks] — which is the same bargain [AssetKind] already makes.
 *
 * ### Provisional until somebody checks it
 *
 * A transcription is a claim about your vehicle, and being confidently wrong about a brake-fluid
 * interval is worse than saying nothing. So a pack carries its [source] and, until somebody has
 * checked it against the manual in their own glovebox, [provisional] — which the screens show. The
 * intervals are meant to be edited: they become ordinary plans the moment they are applied, and
 * nothing re-imposes them afterwards.
 */
data class SchedulePack(
    val id: String,
    val label: String,
    /** Where the numbers came from, verbatim enough to go and check. */
    val source: String,
    /** Which assets this pack is for, and which ones of them. */
    val fit: PackFit,
    val items: List<ScheduleItem>,
    /**
     * Normal or severe service, on the kinds that publish two schedules. Null on the kinds that do
     * not — nobody prints a Schedule B for a house.
     */
    val duty: Duty? = null,
    /** True until a human has checked these numbers against the manual. Shown, not hidden. */
    val provisional: Boolean = true
) {
    /** "12 items · Schedule A" — the line under a pack's name, wherever one is offered. */
    val summary: String
        get() = listOfNotNull("${items.size} items", duty?.label).joinToString(" · ")
}

/**
 * Normal or severe service. Manufacturers publish two schedules and most people are on the first
 * without ever being asked which; the app asks, once, because the answer roughly halves some
 * intervals.
 */
enum class Duty(val key: String, val label: String, val detail: String) {
    NORMAL("normal", "Schedule A", "Ordinary driving: longer trips, mild conditions, no towing"),
    SEVERE("severe", "Schedule B", "Short trips, dust, heat, heavy loads or towing");

    companion object {
        fun of(key: String?): Duty = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * One line of a schedule.
 *
 * The shape is deliberately the same as an [UpkeepPlan]'s, because that is what it becomes — a pack
 * is a list of plans somebody else wrote down. [key] is stable for the life of the pack: it is how
 * a second apply knows this item is already here.
 */
data class ScheduleItem(
    val key: String,
    val title: String,
    val notes: String? = null,
    val everyDays: Int? = null,
    val everyMeter: Long? = null,
    val atMeter: List<Long> = emptyList(),
    val kind: PlanKind = PlanKind.UPKEEP
)

/**
 * What a pack is for.
 *
 * A pack fits a *kind* of asset before it fits a particular one, which is why this is a sealed
 * hierarchy rather than a bag of nullable fields: a home schedule and a vehicle schedule are matched
 * against different facts entirely, and the compiler should be the thing that knows it. It is the
 * same call `logic/AssetKind` makes about a house having no odometer — a shape nobody else's kind
 * can use does not become a null column on everybody.
 */
sealed interface PackFit {

    /** The kind of asset this pack can ever apply to. */
    val kind: AssetKind

    /**
     * A vehicle: make, a substring of the model, a year range, an engine size. Every field that is
     * set has to match; a null field matches anything.
     *
     * Matching is deliberately loose because vPIC's model names carry trim and punctuation that
     * varies by year, and a pack that only matched an exact string would silently stop matching in a
     * model year nobody was watching.
     */
    data class Vehicle(
        val make: String? = null,
        val modelContains: String? = null,
        val years: IntRange? = null,
        val displacementLitres: Double? = null
    ) : PackFit {

        override val kind: AssetKind get() = AssetKind.VEHICLE

        fun matches(facts: VehicleFacts): Boolean {
            if (make != null && !facts.make.equals(make, ignoreCase = true)) return false
            if (modelContains != null && facts.model?.contains(modelContains, ignoreCase = true) != true) return false
            if (years != null && (facts.year == null || facts.year !in years)) return false
            if (displacementLitres != null) {
                val engine = facts.displacementLitres ?: return false
                // vPIC reports 3.6 and 3.6000000000000001 alike; compare like a person would.
                if (kotlin.math.abs(engine - displacementLitres) > 0.05) return false
            }
            return true
        }
    }

    /**
     * A home: the climates it is for, the kinds of building it is for, the features it needs
     * announced, how old the house has to be, and whether there is a loan against it.
     *
     * Every clause that is set has to hold, and an unset clause matches anything — so the pack with
     * no clauses at all is the standing list every house gets, and the one with
     * `needs = setOf(SEPTIC)` never appears for a house on mains drainage.
     *
     * The two structure clauses pull in opposite directions on purpose, and the difference is what
     * a **missing** fact does. [structures] asks for a kind of building and a house whose type
     * nobody has picked does not match it — that is right for the manufactured-home list, which
     * would be nonsense on a condo. [excludeStructures] rules one out, and an unpicked type is *not*
     * ruled out — that is right for the list of jobs on the outside of the building, which almost
     * every home owes and only a condo does not. Silence should cost a household the schedule that
     * is usually wrong, never the schedule that is usually right.
     */
    data class Home(
        val climates: Set<Climate> = emptySet(),
        /** Only these kinds of building. An unpicked type matches nothing here. */
        val structures: Set<HomeStructure> = emptySet(),
        /** Any kind of building but these. An unpicked type is not excluded. */
        val excludeStructures: Set<HomeStructure> = emptySet(),
        val needs: Set<HomeFeature> = emptySet(),
        /** For the jobs that only older housing stock has. Compared against the year built. */
        val builtBefore: Int? = null,
        val needsMortgage: Boolean = false
    ) : PackFit {

        override val kind: AssetKind get() = AssetKind.HOME

        fun matches(facts: HomeFacts): Boolean {
            if (climates.isNotEmpty() && (facts.climate == null || facts.climate !in climates)) return false
            if (structures.isNotEmpty() && (facts.structure == null || facts.structure !in structures)) return false
            if (facts.structure != null && facts.structure in excludeStructures) return false
            if (!facts.features.containsAll(needs)) return false
            if (builtBefore != null && (facts.yearBuilt == null || facts.yearBuilt >= builtBefore)) return false
            if (needsMortgage && !facts.hasMortgage) return false
            return true
        }
    }
}

/**
 * The packs this build ships.
 *
 * Two for vehicles, on purpose. A specific one for the vehicle this was built for, and a **generic**
 * fallback so that a car nothing matches still gets a sensible starting point rather than an empty
 * screen — because an empty screen is where people give up, and "oil every 5,000 miles" is right
 * often enough to be worth offering as something to correct.
 *
 * The home packs are in [HomePacks] and are shaped differently for a reason the file explains: a
 * house is not one schedule but a standing list plus whatever the climate and the plumbing add.
 */
object SchedulePacks {

    /**
     * The recall check, on every vehicle schedule.
     *
     * It is in the packs rather than created behind your back when a vehicle is added, because a
     * plan is a thing this app puts on your week and inventing those unasked is how an app stops
     * being trusted. It is in *both* packs because it has nothing to do with the manufacturer's
     * schedule: no owner's manual tells you to ask NHTSA anything. It is here because the packs are
     * where a vehicle picks up what it should be doing regularly, and this is one of those things.
     */
    val RECALL_CHECK_ITEM = ScheduleItem(
        key = "recall-check",
        title = "Check recalls",
        notes = "Open the vehicle and press Check recalls. Campaigns are opened years after a car " +
            "is built, and the letter goes to whatever address the DMV last had.",
        everyDays = RecallChecks.EVERY_DAYS,
        kind = PlanKind.RECALL_CHECK
    )

    /**
     * Jeep Wrangler JL, 3.6L Pentastar — Schedule A (normal duty).
     *
     * **Transcribed by hand and not yet checked against a manual.** The intervals below are the
     * common published ones for this engine and generation; treat them as a starting point, check
     * the ones that matter against your own book, and edit them — they are ordinary plans the moment
     * they are applied. The ones most worth checking are the fluids: brake, transfer case and axle
     * intervals move around between model years and between the manual and the dealer's version.
     */
    val JEEP_JL_36_NORMAL = SchedulePack(
        id = "jeep-jl-36-a",
        label = "Jeep Wrangler JL 3.6L — Schedule A",
        source = "Jeep Wrangler (JL) owner's manual, Maintenance Schedule A — transcribed by hand",
        fit = PackFit.Vehicle(make = "JEEP", modelContains = "Wrangler", years = 2018..2026, displacementLitres = 3.6),
        duty = Duty.NORMAL,
        items = listOf(
            ScheduleItem(
                key = "engine-oil",
                title = "Engine oil & filter",
                notes = "Or when the oil change indicator asks, whichever comes first.",
                everyMeter = 10_000,
                everyDays = 365
            ),
            ScheduleItem(
                key = "tire-rotation",
                title = "Rotate tires",
                notes = "Done with the oil change.",
                everyMeter = 10_000
            ),
            ScheduleItem(
                key = "cabin-filter",
                title = "Cabin air filter",
                everyMeter = 20_000
            ),
            ScheduleItem(
                key = "engine-air-filter",
                title = "Engine air filter",
                everyMeter = 30_000
            ),
            ScheduleItem(
                key = "brake-fluid",
                title = "Brake fluid flush",
                notes = "Worth checking against your manual — this one varies by model year.",
                everyDays = 3 * 365
            ),
            ScheduleItem(
                key = "transfer-case-fluid",
                title = "Transfer case fluid",
                notes = "Worth checking against your manual.",
                atMeter = listOf(60_000, 120_000, 180_000)
            ),
            ScheduleItem(
                key = "axle-fluid",
                title = "Front & rear axle fluid",
                notes = "Sooner under severe use — Schedule B is 30,000 mile intervals.",
                atMeter = listOf(60_000, 120_000, 180_000)
            ),
            ScheduleItem(
                key = "auto-trans-fluid",
                title = "Automatic transmission fluid & filter",
                notes = "Automatic only. Worth checking against your manual.",
                atMeter = listOf(120_000)
            ),
            ScheduleItem(
                key = "spark-plugs",
                title = "Spark plugs",
                atMeter = listOf(100_000, 200_000)
            ),
            ScheduleItem(
                key = "accessory-drive-belt",
                title = "Accessory drive belt",
                notes = "Inspect at 60,000; replace when it needs it.",
                atMeter = listOf(60_000, 120_000)
            ),
            ScheduleItem(
                key = "engine-coolant",
                title = "Engine coolant",
                notes = "OAT coolant — a long first interval, then every 5 years.",
                atMeter = listOf(150_000)
            ),
            ScheduleItem(
                key = "odometer-reading",
                title = "Odometer reading",
                notes = "Everything measured in miles depends on this. Two readings give a rate, " +
                    "and a rate turns \"every 10,000 miles\" into a date.",
                everyDays = 7,
                kind = PlanKind.METER_READING
            ),
            RECALL_CHECK_ITEM
        )
    )

    /**
     * Anything with wheels — the fallback.
     *
     * Deliberately short and deliberately conservative. It is not a schedule for your car; it is the
     * handful of jobs almost every car has, so that a vehicle nothing matches starts with something
     * to correct rather than nothing at all.
     */
    val GENERIC_VEHICLE = SchedulePack(
        id = "generic-vehicle",
        label = "Any vehicle — a starting point",
        source = "Common practice, not a manufacturer schedule",
        fit = PackFit.Vehicle(),
        duty = Duty.NORMAL,
        items = listOf(
            ScheduleItem("engine-oil", "Engine oil & filter", everyMeter = 5_000, everyDays = 365),
            ScheduleItem("tire-rotation", "Rotate tires", everyMeter = 7_500),
            ScheduleItem("cabin-filter", "Cabin air filter", everyMeter = 15_000),
            ScheduleItem("engine-air-filter", "Engine air filter", everyMeter = 30_000),
            ScheduleItem("brake-fluid", "Brake fluid flush", everyDays = 3 * 365),
            ScheduleItem(
                key = "odometer-reading",
                title = "Odometer reading",
                notes = "Everything measured in miles depends on this.",
                everyDays = 7,
                kind = PlanKind.METER_READING
            ),
            RECALL_CHECK_ITEM
        )
    )

    /** Every vehicle pack, specific ones first — [forVehicle] relies on that order. */
    val vehiclePacks: List<SchedulePack> = listOf(JEEP_JL_36_NORMAL, GENERIC_VEHICLE)

    /** Every home pack, the standing list first — [forHome] relies on that order. */
    val homePacks: List<SchedulePack> = HomePacks.all

    /**
     * Every pack this build ships, of every kind.
     *
     * This is what [byId] reads, and [byId] is what lets a plan created two years ago still say
     * which schedule it came from. So a pack id is permanent: renaming one orphans the provenance on
     * every plan already carrying it.
     */
    val all: List<SchedulePack> = vehiclePacks + homePacks

    fun byId(id: String?): SchedulePack? = all.firstOrNull { it.id == id }

    /**
     * The home schedules that fit, the standing list first.
     *
     * Unlike a vehicle, a home gets *several* of these and is meant to: the standing list every
     * house has, plus whatever its climate, its age, its plumbing and its mortgage add on top. They
     * are separate packs rather than one composed list because a plan has to be able to say where it
     * came from years later, and because facts arrive late — you type the septic tank in six months
     * after the house, and the septic schedule is then simply one more thing to apply.
     */
    fun forHome(facts: HomeFacts): List<SchedulePack> =
        homePacks.filter { pack -> (pack.fit as? PackFit.Home)?.matches(facts) == true }

    /**
     * The packs that fit, best first.
     *
     * The generic pack matches everything, so it is always in the list and always last: the screen
     * shows the match it found *and* the fallback, and lets the person choose. A wrong-but-specific
     * pack silently applied would be the worst outcome here.
     */
    fun forVehicle(facts: VehicleFacts): List<SchedulePack> =
        vehiclePacks.filter { pack -> (pack.fit as? PackFit.Vehicle)?.matches(facts) == true }
}
