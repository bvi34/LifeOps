package com.maintenance.app.logic

/**
 * What a house owes, as data.
 *
 * ### Why a house is several schedules and a car is one
 *
 * A vehicle has one maintenance schedule because a manufacturer wrote one: every 2018 Wrangler
 * built to that specification owes the same list. **Nobody builds a house to a specification and
 * nobody ships it with a manual.** What a house owes is the sum of separate things — the list every
 * building has, plus what winter does to it here, plus what its age brings, plus whichever of a
 * handful of systems happen to be attached to it.
 *
 * So the home catalogue is deliberately many small packs rather than one big one, and a house is
 * normally offered three or four. That shape is what makes the awkward case work: you type the house
 * in on the day you buy it, and six months later you finally write "septic tank" in the field that
 * asks what it has — at which point the septic schedule appears as one more thing to apply, and
 * nothing you already tuned is touched. The alternative, one composed list regenerated from the
 * facts, would either re-impose intervals you had edited or quietly refuse to grow.
 *
 * ### Where the numbers come from
 *
 * Not from a manual, because there isn't one. These are the intervals in common circulation from
 * HVAC trades, roofers, water authorities, chimney sweeps and the fire service, transcribed here by
 * hand. Several of them are genuinely contested — how often a septic tank wants pumping depends on
 * how many people live over it, and a 4-inch media filter lasts six times a 1-inch one — so every
 * pack is [SchedulePack.provisional] and says so on screen. They are a starting point to correct:
 * the moment a pack is applied its items become ordinary plans, yours to re-time or delete, and
 * nothing re-imposes them afterwards.
 *
 * ### What is deliberately not here
 *
 * No item tells anybody whether to refinance, appeal an assessment, or drop a policy. The mortgage
 * pack below schedules the *reading* of documents that arrive once a year and get filed unopened —
 * which is a chore, and this app's business — and stops there. `logic/Loan` takes the same line
 * about not being a payoff optimiser.
 */
object HomePacks {

    private const val YEAR = 365
    private const val HALF_YEAR = 182
    private const val QUARTER = 90

    private const val COMMON_PRACTICE = "Common practice — trade and fire-service guidance, transcribed by hand"

    /**
     * Every house, whatever and wherever it is.
     *
     * The test for being on this list is that the job is owed by a building rather than by a system
     * somebody chose to put in it, and that skipping it costs more than doing it. Three of these are
     * fire (alarms, the dryer vent, the extinguisher), three are water finding its way in (gutters,
     * the roof, the grading), and one — turning the main shut-off — is the single job on the list
     * most likely to be worth more than everything else put together on one particular night.
     */
    val CORE = SchedulePack(
        id = "home-core",
        label = "Home upkeep — the standing list",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(),
        items = listOf(
            ScheduleItem(
                key = "hvac-filter",
                title = "Change the furnace / AC filter",
                notes = "Ninety days is the compromise, not the rule: a 1-inch filter in a dusty " +
                    "house with a dog wants a month, and a 4-inch media filter can go six.",
                everyDays = QUARTER
            ),
            ScheduleItem(
                key = "smoke-co-alarms",
                title = "Test the smoke and CO alarms",
                notes = "Press the button on every one of them, including the ones nobody can reach.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "alarm-batteries",
                title = "Replace the alarm batteries",
                notes = "Sealed ten-year alarms don't want this — they want replacing at ten years, " +
                    "and the date is printed on the back of the unit.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "water-heater-flush",
                title = "Flush the water heater",
                notes = "Sediment is what kills a tank, and a tank fails wet.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "gutters",
                title = "Clear the gutters and downspouts",
                notes = "Spring and autumn. Trees over the roof make it more than that.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "roof-look",
                title = "Look over the roof",
                notes = "From the ground with binoculars is a real inspection: lifted shingles, " +
                    "cracked boots round the vents, anything growing in a valley.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "dryer-vent",
                title = "Clear the dryer vent",
                notes = "The lint trap is not the vent. This one is a fire, not a chore.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "gfci-test",
                title = "Test the GFCI outlets and the breaker panel",
                notes = "Test and reset every GFCI; look for anything warm, scorched or humming.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "extinguisher",
                title = "Check the fire extinguisher",
                notes = "Gauge in the green, pin in, and everybody in the house knows where it is.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "water-shutoff",
                title = "Find and turn the main water shut-off",
                notes = "A valve that has not moved in ten years is a valve that will not move on " +
                    "the night it has to.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "caulk-seals",
                title = "Walk the caulk, seals and weather-stripping",
                notes = "Round the tub and the windows, and the door seals you can see daylight through.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "garage-door",
                title = "Test the garage door's auto-reverse",
                notes = "A length of timber under the door. It should stop and go back up.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "drainage",
                title = "Walk the grading and the downspout run-offs",
                notes = "Water should leave the house, not pool against it. Nearly every wet basement " +
                    "starts as a downspout emptying at the wall.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "radon-test",
                title = "Test for radon",
                notes = "The EPA's advice is to test every home and to test again every couple of " +
                    "years — a kit costs less than the alarm batteries.",
                everyDays = 2 * YEAR
            ),
            ScheduleItem(
                key = "appliance-coils",
                title = "Vacuum the fridge coils",
                everyDays = YEAR
            )
        )
    )

    /** Where it freezes hard enough that the freeze is the thing that breaks the house. */
    val COLD_WINTERS = SchedulePack(
        id = "home-cold",
        label = "Cold winters",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(climates = setOf(Climate.COLD, Climate.TEMPERATE)),
        items = listOf(
            ScheduleItem(
                key = "winterise-taps",
                title = "Shut off and drain the outside taps",
                notes = "And take the hoses off them. A hose left on is what splits the pipe behind " +
                    "the wall, in January, while nobody is home.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "heating-service",
                title = "Have the heating system serviced",
                notes = "Before the first cold week, not during it — that is when nobody can come out.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "attic-insulation",
                title = "Check the attic insulation and ventilation",
                notes = "Ice dams are a ventilation problem wearing a roofing problem's clothes.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "winter-kit",
                title = "Service the snow gear and restock the salt",
                everyDays = YEAR
            )
        )
    )

    /** Where the cooling season is the long one. */
    val HOT_SUMMERS = SchedulePack(
        id = "home-hot",
        label = "Hot summers",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(climates = setOf(Climate.HOT_HUMID, Climate.HOT_DRY)),
        items = listOf(
            ScheduleItem(
                key = "ac-service",
                title = "Have the air conditioning serviced",
                notes = "In spring. A system that is low on refrigerant in June is a system that was " +
                    "low on refrigerant in April.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "condensate-line",
                title = "Clear the AC condensate line",
                notes = "A cup of vinegar down the clean-out. A blocked line is how an air handler " +
                    "floods a ceiling.",
                everyDays = QUARTER
            ),
            ScheduleItem(
                key = "condenser-clear",
                title = "Clear round the outdoor condenser",
                notes = "Two feet of air on every side, and the fins hosed off.",
                everyDays = HALF_YEAR
            )
        )
    )

    /** Where things grow on the house and eat it. */
    val DAMP = SchedulePack(
        id = "home-damp",
        label = "Damp and humid",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(climates = setOf(Climate.HOT_HUMID, Climate.MARINE)),
        items = listOf(
            ScheduleItem(
                key = "termite-inspection",
                title = "Have the house inspected for termites",
                notes = "Annual is the usual interval where they are endemic, and most bond " +
                    "agreements require it to stay in force.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "damp-check",
                title = "Look for damp — crawlspace, basement, under the sinks",
                notes = "A torch and ten minutes. Damp is cheap while it is still a smell.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "exterior-wash",
                title = "Wash the siding and clear the moss",
                everyDays = YEAR
            )
        )
    )

    /**
     * Older housing stock.
     *
     * 1980 is the line because the three jobs below are all about what was normal to install before
     * it and is not normal now. It is a prompt to have somebody look, not a claim that anything is
     * wrong: plenty of 1955 houses have been rewired twice.
     */
    val OLDER_HOUSE = SchedulePack(
        id = "home-older",
        label = "An older house",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(builtBefore = 1980),
        items = listOf(
            ScheduleItem(
                key = "panel-check",
                title = "Have the electrical panel and wiring looked at",
                notes = "Aluminium branch wiring, cloth-insulated cable and Federal Pacific or " +
                    "Zinsco panels are all things a house of this age can be carrying.",
                everyDays = 3 * YEAR
            ),
            ScheduleItem(
                key = "drain-line-scope",
                title = "Have the main drain line scoped",
                notes = "Clay and cast iron are what a house of this age drains through, and roots " +
                    "find both. A camera costs a fraction of a dig.",
                everyDays = 5 * YEAR
            ),
            ScheduleItem(
                key = "supply-lines",
                title = "Check the supply lines and shut-off valves",
                notes = "Braided lines to the washer, the dishwasher and every toilet. They are the " +
                    "cheapest part of the house and the one that floods it.",
                everyDays = YEAR
            )
        )
    )

    val SEPTIC = SchedulePack(
        id = "home-septic",
        label = "Septic system",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.SEPTIC)),
        items = listOf(
            ScheduleItem(
                key = "septic-pump",
                title = "Have the septic tank pumped",
                notes = "Three years is the middle of the usual advice — a big household over a small " +
                    "tank wants two, two people over a large one can go five. Worth setting to yours.",
                everyDays = 3 * YEAR
            ),
            ScheduleItem(
                key = "septic-inspect",
                title = "Have the septic system inspected",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "drain-field-walk",
                title = "Walk the drain field",
                notes = "Looking for standing water, a smell, or grass that is greener over the lines.",
                everyDays = HALF_YEAR
            )
        )
    )

    val WELL = SchedulePack(
        id = "home-well",
        label = "Private well",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.WELL)),
        items = listOf(
            ScheduleItem(
                key = "well-water-test",
                title = "Test the well water",
                notes = "Coliform bacteria and nitrate every year is the standard advice; the wider " +
                    "panel — metals, arsenic, whatever is local — every few years. Nobody else is " +
                    "testing it for you.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "well-service",
                title = "Service the well pump and pressure tank",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "sediment-filter",
                title = "Change the sediment filter",
                everyDays = QUARTER
            )
        )
    )

    val FIREPLACE = SchedulePack(
        id = "home-fireplace",
        label = "Fireplace or wood stove",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.FIREPLACE)),
        items = listOf(
            ScheduleItem(
                key = "chimney-sweep",
                title = "Have the chimney swept and inspected",
                notes = "Annually where it is burned regularly. Creosote is the fire; a cracked liner " +
                    "is the carbon monoxide.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "damper-cap",
                title = "Check the damper and the chimney cap",
                everyDays = YEAR
            )
        )
    )

    val SUMP_PUMP = SchedulePack(
        id = "home-sump",
        label = "Sump pump",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.SUMP_PUMP)),
        items = listOf(
            ScheduleItem(
                key = "sump-test",
                title = "Test the sump pump",
                notes = "A bucket of water down the pit is the whole test. Do it before the wet season, " +
                    "not during the storm.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "sump-battery",
                title = "Replace the backup pump's battery",
                notes = "The backup exists for the storm that takes the power out, which is the same " +
                    "storm that fills the pit.",
                everyDays = 3 * YEAR
            )
        )
    )

    val IRRIGATION = SchedulePack(
        id = "home-irrigation",
        label = "Sprinklers",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.IRRIGATION)),
        items = listOf(
            ScheduleItem(
                key = "irrigation-start",
                title = "Start the system up and walk every head",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "irrigation-blowout",
                title = "Blow the lines out before the freeze",
                notes = "Where it freezes. A head is cheap and a cracked manifold under the lawn is not.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "backflow-test",
                title = "Have the backflow preventer tested",
                notes = "Most water authorities require this annually and will write to you about it — " +
                    "which is the letter that gets filed unopened.",
                everyDays = YEAR
            )
        )
    )

    val POOL = SchedulePack(
        id = "home-pool",
        label = "Pool or hot tub",
        source = COMMON_PRACTICE,
        fit = PackFit.Home(needs = setOf(HomeSystem.POOL)),
        items = listOf(
            ScheduleItem(
                key = "pool-water-test",
                title = "Test the water",
                notes = "Weekly in season. This is the item most likely to want re-timing to your own " +
                    "habit — a hot tub and a pool are not the same job.",
                everyDays = 7
            ),
            ScheduleItem(
                key = "pool-filter",
                title = "Clean or backwash the filter",
                everyDays = 30
            ),
            ScheduleItem(
                key = "pool-season",
                title = "Open or close for the season",
                everyDays = HALF_YEAR
            )
        )
    )

    /**
     * Owning it, as opposed to living in it.
     *
     * These are paperwork, and paperwork is exactly the kind of upkeep that gets missed: nothing
     * breaks when it is skipped, and the cost of skipping it arrives quietly, years later, as an
     * insurance settlement based on what a house cost to build in 2011.
     */
    val OWNERSHIP = SchedulePack(
        id = "home-ownership",
        label = "Owning it — the paperwork",
        source = "Common practice — no schedule but the calendar's",
        fit = PackFit.Home(),
        items = listOf(
            ScheduleItem(
                key = "property-tax",
                title = "Check the property tax bill",
                notes = "Most counties bill twice a year. An assessment can usually be appealed, and " +
                    "usually only within a few weeks of the notice arriving.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "insurance-review",
                title = "Read the homeowner's policy before it renews",
                notes = "Replacement cost drifts behind what building actually costs. Renewal is the " +
                    "cheap moment to fix it; a claim is the expensive one.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "home-inventory",
                title = "Photograph the rooms for the insurer",
                notes = "A claim is settled on what you can show you had. Ten minutes with a phone, " +
                    "kept somewhere that is not the house.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "file-the-paperwork",
                title = "File the year's receipts, manuals and warranties",
                notes = "Improvements are what reduce the capital gain when it is eventually sold, " +
                    "and a receipt nobody kept reduces nothing.",
                everyDays = YEAR
            )
        )
    )

    /**
     * Paying it off.
     *
     * Every item here is *reading something that arrives on its own* — which is the whole of what
     * this app will do about a mortgage. It does not advise, and the notes are there to say what the
     * document is for rather than what to do about it.
     */
    val MORTGAGE = SchedulePack(
        id = "home-mortgage",
        label = "Paying it off",
        source = "Common practice — no schedule but the lender's",
        fit = PackFit.Home(needsMortgage = true),
        items = listOf(
            ScheduleItem(
                key = "escrow-analysis",
                title = "Read the escrow analysis",
                notes = "The lender re-runs it once a year and the payment moves with it. A shortage " +
                    "is nearly always a tax or insurance rise rather than a mistake — but it is worth " +
                    "knowing which, because one of those you can do something about.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "statement-check",
                title = "Check the statement against the balance here",
                notes = "What this app shows is arithmetic on what you typed in; the statement is the " +
                    "lender's own record. They should agree, and if they do not, one of the two " +
                    "numbers you have been relying on is wrong.",
                everyDays = YEAR
            ),
            ScheduleItem(
                key = "pmi-check",
                title = "Ask whether the mortgage insurance can come off",
                notes = "In the US a lender must drop it at 78% of the original value and will " +
                    "normally consider a written request at 80%. Nobody rings to tell you, and it is " +
                    "money leaving every month for nothing you own.",
                everyDays = HALF_YEAR
            ),
            ScheduleItem(
                key = "interest-statement",
                title = "Put the interest statement with the tax papers",
                notes = "It arrives in January, alone, and is needed in April.",
                everyDays = YEAR
            )
        )
    )

    /**
     * Every home pack, the standing lists first.
     *
     * The order is the order they are offered in, and it is deliberate: what every house owes, then
     * what this house's weather and age add, then its systems, then the paperwork. A screen that led
     * with the mortgage would be a different app.
     */
    val all: List<SchedulePack> = listOf(
        CORE,
        COLD_WINTERS,
        HOT_SUMMERS,
        DAMP,
        OLDER_HOUSE,
        SEPTIC,
        WELL,
        FIREPLACE,
        SUMP_PUMP,
        IRRIGATION,
        POOL,
        OWNERSHIP,
        MORTGAGE
    )
}
