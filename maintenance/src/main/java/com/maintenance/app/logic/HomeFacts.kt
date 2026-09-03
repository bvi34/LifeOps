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
 * So the lookup reads what is already on the asset — the address, the year built, and the plain
 * sentence somebody typed about what the place has — and turns it into the handful of facts a home's
 * upkeep actually turns on: what winter does here, how old the house is, and which of the systems
 * that carry their own schedules are present. That is enough to *choose* schedules, which is the
 * same job the VIN decode does and the only job either of them has.
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
    val yearBuilt: Int? = null,
    /** The systems that carry schedules of their own, read out of what the owner wrote. */
    val systems: Set<HomeSystem> = emptySet(),
    /** Whether there is a loan against this asset. Paying a house off is upkeep of its own kind. */
    val hasMortgage: Boolean = false,
    /** What could not be worked out, as a sentence to show. Never a refusal. */
    val note: String? = null
) {
    val isEmpty: Boolean
        get() = climate == null && yearBuilt == null && systems.isEmpty() && !hasMortgage

    /** "Built 1974 · Cold winters" — the line a heading uses. */
    val descriptor: String
        get() = listOfNotNull(
            yearBuilt?.let { "Built $it" },
            climate?.label
        ).joinToString(" · ")

    /** "Septic tank · Private well · Fireplace" — what it was found to have. */
    val detail: String
        get() = systems.sortedBy { it.ordinal }.joinToString(" · ") { it.label }
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

/**
 * The parts of a house that bring a schedule with them.
 *
 * Not an inventory — the app is not trying to know what is in your kitchen. These are exactly the
 * six things whose presence changes what is owed and how often: a tank in the garden that has to be
 * pumped, a well that has to be tested, a flue that has to be swept, a pump that has to be tested
 * before the rain rather than during it, lines that have to be blown out before the freeze, and a
 * pool, which is a part-time job.
 *
 * [words] is how each one gets recognised in a sentence somebody typed in their own words, which is
 * the only way this app asks for them — a form with six checkboxes on it is a form that gets half
 * filled in, and "septic, sump pump in the basement, gas fire in the lounge" is what people write.
 */
enum class HomeSystem(val key: String, val label: String, val words: List<String>) {
    SEPTIC("septic", "Septic tank", listOf("septic", "leach field", "drain field", "drainfield")),
    WELL("well", "Private well", listOf("well", "wellhead", "borehole", "artesian")),
    FIREPLACE(
        "fireplace", "Fireplace or stove",
        listOf("fireplace", "fire place", "wood stove", "woodstove", "wood burner", "log burner", "chimney", "flue")
    ),
    SUMP_PUMP("sump", "Sump pump", listOf("sump")),
    IRRIGATION("irrigation", "Sprinklers", listOf("sprinkler", "irrigation", "drip line")),
    POOL("pool", "Pool or hot tub", listOf("pool", "hot tub", "hottub", "jacuzzi", "spa"));

    companion object {
        fun of(key: String?): HomeSystem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Reading a house off its own fields.
 *
 * There is no network here and there is no button that starts one. What there is instead is the
 * observation that a household has *already typed in* everything a home schedule needs to be chosen
 * — the address, the year, and a sentence about what the place has — and that nothing was ever done
 * with it. This turns that into an answer.
 *
 * All three readings are lenient in the same way the rest of this module is: a missing ZIP is a
 * note rather than an error, an unparseable year is simply absent, and the systems are matched on
 * the words people actually write rather than on a controlled vocabulary.
 */
object HomeLookup {

    /**
     * The facts, from what is on the asset.
     *
     * [address], [systems] and [yearBuilt] are the home kind's own fields; [hasMortgage] is whether
     * a loan row exists against the asset, which the caller knows and this file cannot.
     */
    fun read(
        address: String?,
        yearBuilt: String?,
        systems: String?,
        hasMortgage: Boolean = false
    ): HomeFacts {
        val zip = zipIn(address)
        val climate = climateOf(zip)
        return HomeFacts(
            zip = zip,
            climate = climate,
            yearBuilt = yearOf(yearBuilt),
            systems = systemsIn(systems),
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
     * from a menu of five before it can be offered anything — is a form nobody fills in.
     */
    fun climateOf(zip: String?): Climate? {
        val prefix = zip?.take(3)?.toIntOrNull() ?: return null
        return ZIP_PREFIXES.firstOrNull { prefix in it.first }?.second
    }

    /**
     * The systems named in a sentence.
     *
     * The text is cut into phrases on the punctuation people separate things with, and a phrase
     * claims a system when it names one **and does not deny it** — so "septic tank, no sprinklers"
     * finds the tank and not the sprinklers. Negation is checked per phrase rather than per document
     * because "no pool" and "sump pump" in the same paragraph must not cancel each other out.
     */
    fun systemsIn(text: String?): Set<HomeSystem> {
        if (text.isNullOrBlank()) return emptySet()
        val found = mutableSetOf<HomeSystem>()
        text.lowercase().split(*PHRASE_BREAKS).forEach { phrase ->
            if (phrase.isBlank() || phrase.words().any { it in NEGATIONS }) return@forEach
            HomeSystem.entries.forEach { system ->
                if (system.words.any { phrase.names(it) }) found += system
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
