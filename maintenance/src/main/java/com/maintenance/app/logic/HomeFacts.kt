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
    /** What winter and summer do here, as far as the ZIP can say. A guess, and shown as one. */
    val climate: Climate? = null,
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
    val isEmpty: Boolean
        get() = climate == null && structure == null && yearBuilt == null &&
            features.isEmpty() && !hasMortgage

    /** "Manufactured home · Built 1974 · Cold winters" — the line a heading uses. */
    val descriptor: String
        get() = listOfNotNull(
            structure?.label,
            yearBuilt?.let { "Built $it" },
            climate?.label
        ).joinToString(" · ")

    /** "Septic system · Private well · Solar panels" — what it was announced to have. */
    val detail: String
        get() = features.sortedBy { it.ordinal }.joinToString(" · ") { it.label }
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
        structure: String? = null,
        yearBuilt: String? = null,
        features: String? = null,
        hasMortgage: Boolean = false
    ): HomeFacts {
        val zip = zipIn(address)
        val climate = climateOf(zip)
        return HomeFacts(
            zip = zip,
            climate = climate,
            structure = HomeStructure.of(structure?.trim()),
            yearBuilt = yearOf(yearBuilt),
            features = featuresIn(features),
            hasMortgage = hasMortgage,
            note = when {
                address.isNullOrBlank() ->
                    "No address yet, so nothing here knows what winter does at this house."
                zip == null ->
                    "No ZIP code in the address, so the climate is the one thing this can't work out."
                climate == null ->
                    "That ZIP code isn't one this knows — the climate schedules are all listed below " +
                        "so you can pick the right one yourself."
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
     * A ZIP code's climate, or null when it falls outside the table.
     *
     * **This is coarse and it is meant to be.** The table below is ZIP prefixes — the first three
     * digits, which run in geographic order — assigned to the five climates by the state or the part
     * of a state they cover. It gets Vermont and Florida right and it cannot get California right,
     * because California is four climates and one of them is a desert forty miles from a beach.
     *
     * That is survivable because of what the answer is *for*: it puts a schedule at the top of a
     * list of schedules, all of which can be applied by hand, and the screen says out loud that it
     * is a guess from the ZIP. The alternative — asking every household to classify its own climate
     * from a menu of five before it can be offered anything — is one more question nobody answers.
     */
    fun climateOf(zip: String?): Climate? {
        val prefix = zip?.take(3)?.toIntOrNull() ?: return null
        return ZIP_PREFIXES.firstOrNull { prefix in it.first }?.second
    }

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
    private val ZIP_PREFIXES: List<Pair<IntRange, Climate>> = listOf(
        6..9 to Climate.HOT_HUMID,        // Puerto Rico, US Virgin Islands
        10..69 to Climate.COLD,           // New England: MA RI NH ME VT CT
        70..89 to Climate.TEMPERATE,      // New Jersey
        100..196 to Climate.COLD,         // New York, Pennsylvania
        197..219 to Climate.TEMPERATE,    // Delaware, DC, Maryland
        220..268 to Climate.TEMPERATE,    // Virginia, West Virginia
        270..289 to Climate.TEMPERATE,    // North Carolina
        290..299 to Climate.HOT_HUMID,    // South Carolina
        300..349 to Climate.HOT_HUMID,    // Georgia, Florida
        350..399 to Climate.HOT_HUMID,    // Alabama, Tennessee, Mississippi
        400..427 to Climate.TEMPERATE,    // Kentucky
        430..479 to Climate.COLD,         // Ohio, Indiana
        480..499 to Climate.COLD,         // Michigan
        500..528 to Climate.COLD,         // Iowa
        530..567 to Climate.COLD,         // Wisconsin, Minnesota
        570..599 to Climate.COLD,         // Dakotas, Montana
        600..629 to Climate.COLD,         // Illinois
        630..658 to Climate.TEMPERATE,    // Missouri
        660..679 to Climate.TEMPERATE,    // Kansas
        680..693 to Climate.COLD,         // Nebraska
        700..729 to Climate.HOT_HUMID,    // Louisiana, Arkansas
        730..749 to Climate.HOT_HUMID,    // Oklahoma
        750..794 to Climate.HOT_HUMID,    // Texas, east of the dry line
        795..799 to Climate.HOT_DRY,      // West Texas, El Paso
        800..816 to Climate.COLD,         // Colorado
        820..838 to Climate.COLD,         // Wyoming, Idaho
        840..847 to Climate.COLD,         // Utah — cold and dry; the freeze is what changes the jobs
        850..865 to Climate.HOT_DRY,      // Arizona
        870..884 to Climate.HOT_DRY,      // New Mexico
        889..898 to Climate.HOT_DRY,      // Nevada
        900..931 to Climate.MARINE,       // Southern and coastal California
        932..935 to Climate.HOT_DRY,      // The Central Valley and the desert
        936..961 to Climate.MARINE,       // Central and northern California
        967..968 to Climate.HOT_HUMID,    // Hawaii
        970..989 to Climate.MARINE,       // Oregon, western Washington
        990..994 to Climate.COLD,         // Eastern Washington
        995..999 to Climate.COLD          // Alaska
    )
}

/**
 * What the weather does to a building here.
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
