package com.maintenance.app.logic

/**
 * What a house says about itself, once the fields on it have been read.
 *
 * This is the home's answer to [VehicleFacts], and it is deliberately not the same shape of thing.
 * A vehicle carries a number that a public decoder turns into a specification; **a house carries an
 * address, and an address is not a specification — it is the household**. There is no request this
 * app could send about a home that is a question about a *model* rather than a question about the
 * people who live in it, which is the test `data/net/VehicleLookupClient` documents and the reason
 * the whole of this file runs offline.
 *
 * So the lookup reads what is already on the asset — where it is, what kind of building it is, how
 * old it is, and what the household has announced it has — and turns that into the handful of facts
 * a home's upkeep actually turns on. That is enough to *choose* schedules, which is the same job the
 * VIN decode does and the only job either of them has.
 *
 * Every field is nullable or empty-able, because every one of them genuinely is unknown on the day
 * you type the house in. Nothing here refuses an answer; the most it does is say what it could not
 * work out, in [note].
 */
data class HomeFacts(
    /** The five digits found in the address, when there were any. */
    val zip: String? = null,
    /** Where in the country this is — what winter does here, and what the weather does at its worst. */
    val region: Region? = null,
    /** Whether [region] was picked or worked out. The screen says which; a guess should look like one. */
    val regionSource: RegionSource = RegionSource.NONE,
    /** What kind of building it is, which is what decides what it structurally owes. */
    val structure: HomeStructure? = null,
    val yearBuilt: Int? = null,
    /** What the household ticked that it has. Each one carries a schedule. */
    val features: Set<HomeFeature> = emptySet(),
    /** Whether there is a loan against this asset. Paying a house off is upkeep of its own kind. */
    val hasMortgage: Boolean = false,
    /** What could not be worked out, as a sentence to show. Never a refusal. */
    val note: String? = null
) {
    /** What the weather does here day to day. A property of the region, never stored beside it. */
    val climate: Climate? get() = region?.climate

    /** What the weather does here at its worst, which is a different list of jobs. */
    val hazards: Set<Hazard> get() = region?.hazards.orEmpty()

    val isEmpty: Boolean
        get() = region == null && structure == null && yearBuilt == null &&
            features.isEmpty() && !hasMortgage

    /** "Manufactured or mobile home · Built 1974 · New England" — the line a heading uses. */
    val descriptor: String
        get() = listOfNotNull(
            structure?.label,
            yearBuilt?.let { "Built $it" },
            region?.label
        ).joinToString(" · ")

    /** "Septic system · Private well · Solar panels" — what it was announced to have. */
    val detail: String
        get() = features.sortedBy { it.ordinal }.joinToString(" · ") { it.label }
}

/**
 * Where a region came from, which the screen says out loud.
 *
 * The distinction is the whole reason the region is a field at all. A ZIP prefix is a coarse guess
 * and a household should be able to see that it is one and overrule it in a tap; an answer somebody
 * picked is theirs and nothing re-derives it afterwards.
 */
enum class RegionSource {
    /** Somebody chose it. Nothing overrides this. */
    PICKED,

    /** Worked out from the ZIP in the address, and shown as a guess. */
    ZIP,

    /** Neither — no region, and no climate or hazard schedules offered. */
    NONE
}

/**
 * What kind of building it is.
 *
 * This is the field the rest of the catalogue leans on hardest, because the difference between
 * these four is not cosmetic: a manufactured home sits on piers that settle and is skirted rather
 * than founded, and a condo owner does not own the roof they would otherwise be told to go and look
 * at. Four values, because four is what changes the list.
 */
enum class HomeStructure(val key: String, val label: String, val detail: String) {
    CONVENTIONAL("conventional", "Site-built house", "Built where it stands, on its own foundation"),
    MANUFACTURED(
        "manufactured", "Manufactured or mobile home",
        "Built in a factory and set on piers — skirting, anchors and levelling of its own"
    ),
    TOWNHOUSE("townhouse", "Townhouse or row house", "Your own roof and walls, shared on one or both sides"),
    CONDO("condo", "Condo or apartment", "The association owns the envelope; you own what is inside it");

    companion object {
        fun of(key: String?): HomeStructure? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The things a house can be announced to have, each of which brings a schedule with it.
 *
 * Not an inventory — the app is not trying to know what is in your kitchen. The test for being on
 * this list is that ticking it *changes what is owed*: a tank in the garden that has to be pumped, a
 * well that has to be tested, a flue that has to be swept, panels whose output is worth reading once
 * a year, a pool, which is a part-time job.
 *
 * [words] is how each one was recognised back when this field was free text, and is kept because a
 * value can still arrive that way — from a backup, or from a row written before the picker existed.
 * The picker is the input; the words are the fallback.
 */
enum class HomeFeature(
    val key: String,
    val label: String,
    val detail: String,
    val words: List<String>
) {
    SEPTIC(
        "septic", "Septic system", "Rather than a mains sewer",
        listOf("septic", "leach field", "drain field", "drainfield")
    ),
    WELL(
        "well", "Private well", "Rather than a mains supply",
        listOf("well", "wellhead", "borehole", "artesian")
    ),
    FUEL_GAS(
        "gas", "Gas or propane", "Anything in the house burns it",
        listOf("gas", "propane", "lpg", "natural gas")
    ),
    ALL_ELECTRIC(
        "electric", "All-electric", "No combustion in the building at all",
        listOf("all electric", "all-electric", "heat pump", "mini-split", "mini split")
    ),
    SOLAR(
        "solar", "Solar panels", "Rooftop or ground-mount PV",
        listOf("solar", "photovoltaic", "pv array")
    ),
    AIR_CONDITIONING(
        "ac", "Central air conditioning", "Ducted cooling, wherever the house is",
        listOf("central air", "air conditioning", "air con", "a/c", "central ac")
    ),
    FIREPLACE(
        "fireplace", "Fireplace or wood stove", "Anything with a flue",
        listOf("fireplace", "fire place", "wood stove", "woodstove", "wood burner", "log burner", "chimney", "flue")
    ),
    SUMP_PUMP(
        "sump", "Sump pump", "With or without a battery backup",
        listOf("sump")
    ),
    IRRIGATION(
        "irrigation", "Sprinkler system", "In-ground irrigation, and its backflow preventer",
        listOf("sprinkler", "irrigation", "drip line")
    ),
    POOL(
        "pool", "Pool or hot tub", "Anything holding treated water",
        listOf("pool", "hot tub", "hottub", "jacuzzi", "spa")
    ),
    DECK(
        "deck", "Deck or porch", "Timber or composite, and its rails",
        listOf("deck", "porch", "veranda", "verandah")
    ),
    GENERATOR(
        "generator", "Standby generator", "Whole-house, on gas or propane",
        listOf("generator", "genset", "standby power")
    );

    companion object {
        fun of(key: String?): HomeFeature? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Reading a house off its own fields.
 *
 * There is no network here and there is no button that starts one. What there is instead is the
 * observation that a household has *already told the app* everything a home schedule needs to be
 * chosen — where the house is, what it is, how old it is, and what it has — and that nothing was
 * ever done with it. This turns that into an answer.
 *
 * Two of the four readings are now pickers and need no interpretation at all; the ZIP is dug out of
 * an address people write on three lines, and the features are read leniently enough that a value
 * which is not a list of keys still gets understood. All of it is forgiving in the same way the rest
 * of this module is: a missing ZIP is a note rather than an error, and an unparseable year is simply
 * absent.
 */
object HomeLookup {

    /**
     * The facts, from what is on the asset.
     *
     * [address], [structure], [yearBuilt] and [features] are the home kind's own fields, passed as
     * they are stored; [hasMortgage] is whether a loan row exists against the asset, which the
     * caller knows and this file cannot.
     */
    fun read(
        address: String? = null,
        region: String? = null,
        structure: String? = null,
        yearBuilt: String? = null,
        features: String? = null,
        hasMortgage: Boolean = false
    ): HomeFacts {
        val zip = zipIn(address)
        // A region somebody picked wins outright, and the ZIP is only consulted when nobody has.
        // The alternative — re-deriving it every read and treating the picked value as a hint —
        // is the shape that argues with people about where they live.
        val picked = Region.of(region)
        val fromZip = regionOf(zip)
        val resolved = picked ?: fromZip
        return HomeFacts(
            zip = zip,
            region = resolved,
            regionSource = when {
                picked != null -> RegionSource.PICKED
                fromZip != null -> RegionSource.ZIP
                else -> RegionSource.NONE
            },
            structure = HomeStructure.of(structure?.trim()),
            yearBuilt = yearOf(yearBuilt),
            features = featuresIn(features),
            hasMortgage = hasMortgage,
            note = when {
                picked != null -> null
                address.isNullOrBlank() ->
                    "No region, and no address to guess one from — so nothing here knows whether the " +
                        "outside taps want draining in October or the shutters want finding before " +
                        "June. Pick one in Edit and the seasonal schedules follow."
                zip == null ->
                    "No ZIP code in the address to guess a region from — pick one and the climate " +
                        "schedules follow."
                fromZip == null ->
                    "That ZIP code isn't one this knows. Pick a region and the climate schedules follow."
                else -> null
            }
        )
    }

    /**
     * The last five-digit group in an address.
     *
     * The *last*, because an address is written top to bottom and the ZIP is at the bottom, under a
     * house number that is also digits. A ZIP+4 is accepted and the +4 dropped: nothing here is
     * finer-grained than a ZIP, and the four extra digits are a delivery route.
     */
    fun zipIn(address: String?): String? {
        if (address.isNullOrBlank()) return null
        return ZIP.findAll(address).lastOrNull()?.groupValues?.get(1)
    }

    /**
     * The region a ZIP code falls in, or null when it falls outside the table.
     *
     * This is the lookup, and it is the whole of it: a table of ZIP prefixes — the first three
     * digits, which run in geographic order — each pointing at a [Region], which carries the climate
     * and whatever hazards are worth preparing for. Military mail (090–098) and anything the table
     * does not cover answer null rather than guessing, because a wrong region is worse than none:
     * none shows the whole catalogue, and wrong shows a confident short list.
     */
    fun regionOf(zip: String?): Region? {
        val prefix = zip?.take(3)?.toIntOrNull() ?: return null
        return ZIP_PREFIXES.firstOrNull { prefix in it.first }?.second
    }

    /** The climate a ZIP falls in — the region's, since a climate is never stored on its own. */
    fun climateOf(zip: String?): Climate? = regionOf(zip)?.climate

    /**
     * The features a stored value announces.
     *
     * The field is a picker, so the ordinary case is a list of keys and this is a lookup. The
     * fallback exists because the value has not always been a list of keys and need not be one: a
     * token that is not a feature key is left in the pile, and anything left over is read the way
     * the old free-text field was read — cut into phrases, and a phrase claims a feature when it
     * names one and does not deny it. So *"septic tank, no sprinklers"* still finds the tank and not
     * the sprinklers, and a row written before the picker existed still chooses the right schedules.
     */
    fun featuresIn(raw: String?): Set<HomeFeature> {
        if (raw.isNullOrBlank()) return emptySet()
        val found = mutableSetOf<HomeFeature>()
        val leftover = mutableListOf<String>()
        raw.split(',').forEach { token ->
            val key = token.trim()
            val feature = HomeFeature.of(key)
            if (feature != null) found += feature else if (key.isNotEmpty()) leftover += key
        }
        if (leftover.isNotEmpty()) found += wordsIn(leftover.joinToString(", "))
        return found
    }

    /** The old reading, kept as the fallback [featuresIn] falls back to. */
    private fun wordsIn(text: String): Set<HomeFeature> {
        val found = mutableSetOf<HomeFeature>()
        text.lowercase().split(*PHRASE_BREAKS).forEach { phrase ->
            if (phrase.isBlank() || phrase.words().any { it in NEGATIONS }) return@forEach
            HomeFeature.entries.forEach { feature ->
                if (feature.words.any { phrase.names(it) }) found += feature
            }
        }
        return found
    }

    /** A year, when the field holds one that could be a building's. Anything else is absent. */
    private fun yearOf(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it in 1500..2200 }

    /**
     * Whether a phrase names [term] as a word rather than inside another one.
     *
     * "Stairwell" is not a well and "spare room" is not a spa. Multi-word terms are matched the same
     * way, on the boundaries at either end.
     */
    private fun String.names(term: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(term, from)
            if (at < 0) return false
            val before = getOrNull(at - 1)
            val after = getOrNull(at + term.length)
            if (!before.isWordChar() && !after.isWordChar()) return true
            from = at + 1
        }
    }

    private fun Char?.isWordChar(): Boolean = this != null && (isLetterOrDigit() || this == '\'')

    private fun String.words(): List<String> = split(' ', '\t', '\n').map { it.trim('.', '(', ')', '"') }

    private val ZIP = Regex("""\b(\d{5})(?:-\d{4})?\b""")

    private val PHRASE_BREAKS = charArrayOf('\n', ',', ';', '.', '/', '|', '(', ')')

    /** The words that turn "septic" into "no septic". */
    private val NEGATIONS = setOf("no", "not", "none", "never", "without", "neither", "nor")

    /**
     * ZIP prefix ranges, in the order they are searched — first match wins, so a narrow range is
     * written above the wide one it sits inside.
     *
     * The comment on each line is the state or region the range covers, so the table can be checked
     * against a postal reference rather than believed.
     */
    private val ZIP_PREFIXES: List<Pair<IntRange, Region>> = listOf(
        6..9 to Region.CARIBBEAN,           // Puerto Rico, US Virgin Islands
        10..69 to Region.NEW_ENGLAND,       // MA RI NH ME VT CT
        70..89 to Region.MID_ATLANTIC,      // New Jersey
        100..196 to Region.NORTHEAST,       // New York, Pennsylvania
        197..219 to Region.MID_ATLANTIC,    // Delaware, DC, Maryland
        220..268 to Region.MID_ATLANTIC,    // Virginia, West Virginia
        270..289 to Region.MID_ATLANTIC,    // North Carolina
        290..299 to Region.SOUTHEAST,       // South Carolina
        300..349 to Region.SOUTHEAST,       // Georgia, Florida
        350..399 to Region.DEEP_SOUTH,      // Alabama, Tennessee, Mississippi
        400..427 to Region.OHIO_VALLEY,     // Kentucky
        430..479 to Region.GREAT_LAKES,     // Ohio, Indiana
        480..499 to Region.GREAT_LAKES,     // Michigan
        500..528 to Region.UPPER_MIDWEST,   // Iowa
        530..567 to Region.UPPER_MIDWEST,   // Wisconsin, Minnesota
        570..588 to Region.UPPER_MIDWEST,   // The Dakotas
        590..599 to Region.MOUNTAIN_WEST,   // Montana
        600..629 to Region.UPPER_MIDWEST,   // Illinois
        630..679 to Region.GREAT_PLAINS,    // Missouri, Kansas
        680..693 to Region.UPPER_MIDWEST,   // Nebraska
        700..714 to Region.GULF_COAST,      // Louisiana
        716..749 to Region.SOUTH_CENTRAL,   // Arkansas, Oklahoma
        750..794 to Region.GULF_COAST,      // Texas, east of the dry line
        795..799 to Region.DRY_SOUTHWEST,   // West Texas, El Paso
        800..816 to Region.MOUNTAIN_WEST,   // Colorado
        820..847 to Region.MOUNTAIN_WEST,   // Wyoming, Idaho, Utah
        850..865 to Region.DRY_SOUTHWEST,   // Arizona
        870..884 to Region.DRY_SOUTHWEST,   // New Mexico
        889..898 to Region.DRY_SOUTHWEST,   // Nevada
        900..931 to Region.CALIFORNIA,      // Southern and coastal California
        932..935 to Region.DRY_SOUTHWEST,   // The Central Valley and the desert
        936..961 to Region.CALIFORNIA,      // Central and northern California
        967..968 to Region.HAWAII,          // Hawaii
        970..989 to Region.PACIFIC_NORTHWEST, // Oregon, western Washington
        990..994 to Region.MOUNTAIN_WEST,   // Eastern Washington
        995..999 to Region.ALASKA           // Alaska
    )
}

/**
 * What the weather does to a building here, day to day.
 *
 * Five, because five is what changes the jobs. A house that freezes has taps to drain and a heating
 * system to service; a house that is hot and wet has an air conditioner, termites and moss; a house
 * that is hot and dry has neither the termites nor the ice dams. Anything finer than this is a
 * distinction the schedules would not draw anyway.
 */
enum class Climate(val key: String, val label: String, val detail: String) {
    COLD("cold", "Cold winters", "Hard freezes, snow load, a heating season"),
    TEMPERATE("temperate", "Four seasons", "It freezes, but not for long"),
    HOT_HUMID("hot_humid", "Hot and humid", "Long cooling season, damp, things that eat wood"),
    HOT_DRY("hot_dry", "Hot and dry", "Long cooling season, sun and dust rather than damp"),
    MARINE("marine", "Mild and wet", "Rarely freezes, rains for months, everything grows on everything");

    companion object {
        fun of(key: String?): Climate? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What the weather does here **at its worst** — which is a different list of jobs from what it does
 * on an ordinary Tuesday.
 *
 * This is the half of a region that a climate cannot carry. Miami and Houston are both hot and
 * humid; only one of them is a place where the roof straps and the shutters want checking before
 * June. Boulder and Burlington are both cold; only one of them has thirty feet of ground round the
 * house that has to stay clear of anything that burns.
 *
 * All four are **seasonal preparation** rather than reaction: the jobs are worth having on a
 * schedule precisely because the moment they matter is the moment it is too late to start. Nothing
 * here reacts to a forecast — Maintenance says what is owed and LifeOps says when, and neither of
 * them watches the weather.
 */
enum class Hazard(val key: String, val label: String, val detail: String) {
    HURRICANE("hurricane", "Hurricanes", "A season with a start date, which is what makes it schedulable"),
    WILDFIRE("wildfire", "Wildfire", "Embers rather than flames are what take houses, and they arrive early"),
    SEVERE_STORM("severe_storm", "Hail and tornadoes", "Sudden, local, and insured on a deadline"),
    EARTHQUAKE("earthquake", "Earthquakes", "No season at all, which is why the preparation has to be on a clock");

    companion object {
        fun of(key: String?): Hazard? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where in the country a house is, in the only terms upkeep cares about.
 *
 * A region is the join between a ZIP code and a schedule: it carries **one** [climate] and whatever
 * [hazards] are worth preparing for on a clock, and the packs are keyed on those rather than on the
 * region itself — so a hurricane list is written once and reaches Florida, the Gulf, the Carolinas,
 * Hawaii and Puerto Rico without any of them being named in it.
 *
 * ### It is coarse, and the field exists so it can be corrected
 *
 * These eighteen are drawn on ZIP prefixes, which run in geographic order, and they are drawn
 * roughly. "The dry Southwest" contains California's Central Valley, which is not in the Southwest;
 * Texas is filed with the Gulf Coast although most of it is nowhere near the water. Any line drawn
 * here is wrong for somebody, and the answer to that is not a finer table — it is that **the region
 * is a field somebody can pick**, and the ZIP only fills it in. See [RegionSource].
 */
enum class Region(
    val key: String,
    val label: String,
    val detail: String,
    val climate: Climate,
    val hazards: Set<Hazard> = emptySet()
) {
    NEW_ENGLAND("new_england", "New England", "Maine down to Connecticut", Climate.COLD),
    NORTHEAST("northeast", "New York and Pennsylvania", "The rest of the cold Northeast", Climate.COLD),
    MID_ATLANTIC(
        "mid_atlantic", "The Mid-Atlantic",
        "New Jersey to North Carolina, and the Virginias", Climate.TEMPERATE
    ),
    SOUTHEAST(
        "southeast", "The Southeast",
        "South Carolina, Georgia and Florida", Climate.HOT_HUMID, setOf(Hazard.HURRICANE)
    ),
    DEEP_SOUTH(
        "deep_south", "The Deep South",
        "Alabama, Mississippi and Tennessee", Climate.HOT_HUMID, setOf(Hazard.SEVERE_STORM)
    ),
    OHIO_VALLEY("ohio_valley", "The Ohio Valley", "Kentucky", Climate.TEMPERATE),
    GREAT_LAKES("great_lakes", "The Great Lakes", "Ohio, Indiana and Michigan", Climate.COLD),
    UPPER_MIDWEST(
        "upper_midwest", "The Upper Midwest",
        "Iowa, Wisconsin, Minnesota, the Dakotas, Illinois and Nebraska",
        Climate.COLD, setOf(Hazard.SEVERE_STORM)
    ),
    GREAT_PLAINS(
        "great_plains", "The Plains", "Missouri and Kansas",
        Climate.TEMPERATE, setOf(Hazard.SEVERE_STORM)
    ),
    SOUTH_CENTRAL(
        "south_central", "Arkansas and Oklahoma", "Tornado alley's southern end",
        Climate.HOT_HUMID, setOf(Hazard.SEVERE_STORM)
    ),
    GULF_COAST(
        "gulf_coast", "The Gulf Coast and Texas", "Louisiana and Texas east of the dry line",
        Climate.HOT_HUMID, setOf(Hazard.HURRICANE, Hazard.SEVERE_STORM)
    ),
    MOUNTAIN_WEST(
        "mountain_west", "The Mountain West",
        "Montana, Idaho, Wyoming, Colorado, Utah and eastern Washington",
        Climate.COLD, setOf(Hazard.WILDFIRE)
    ),
    DRY_SOUTHWEST(
        "dry_southwest", "The dry Southwest",
        "Arizona, New Mexico, Nevada, West Texas and California's Central Valley",
        Climate.HOT_DRY, setOf(Hazard.WILDFIRE)
    ),
    CALIFORNIA(
        "california", "California", "The coast and the valleys either side of it",
        Climate.MARINE, setOf(Hazard.EARTHQUAKE, Hazard.WILDFIRE)
    ),
    PACIFIC_NORTHWEST(
        "pacific_northwest", "The Pacific Northwest", "Oregon and western Washington",
        Climate.MARINE, setOf(Hazard.EARTHQUAKE, Hazard.WILDFIRE)
    ),
    ALASKA("alaska", "Alaska", "Its own thing entirely", Climate.COLD),
    HAWAII("hawaii", "Hawaii", "Warm, wet and in the path of things", Climate.HOT_HUMID, setOf(Hazard.HURRICANE)),
    CARIBBEAN(
        "caribbean", "Puerto Rico and the Virgin Islands", "Warm, wet and in the path of things",
        Climate.HOT_HUMID, setOf(Hazard.HURRICANE)
    );

    companion object {
        fun of(key: String?): Region? = entries.firstOrNull { it.key == key?.trim() }
    }
}
