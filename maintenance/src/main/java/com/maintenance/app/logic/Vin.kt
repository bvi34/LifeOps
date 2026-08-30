package com.maintenance.app.logic

/**
 * What can be said about a VIN without asking anyone.
 *
 * Maintenance has no network permission and no decoder service behind it, which rules out the
 * usual "type a VIN, get a car" trick. What it can do is everything the number itself carries: a
 * VIN is a *self-checking* string — 17 characters from a restricted alphabet, with a check digit in
 * the ninth position computed from the other sixteen — and the tenth character is the model year.
 * So a typo is catchable on the spot, offline, which is the thing that actually matters when you
 * are copying seventeen characters off a door jamb.
 *
 * The important design decision here is that a failed check digit is **not** a rejected VIN.
 * Check-digit validation is a North American requirement; plenty of vehicles built elsewhere carry
 * a number that is a perfectly valid VIN and fails this arithmetic. So [problem] grades the
 * failure: a wrong length or an illegal letter is a typo, full stop, while a check digit that
 * disagrees is *worth mentioning and nothing more*. The app shows it and stores what you typed.
 */
object Vin {

    const val LENGTH = 17

    /**
     * How much of a VIN describes the model rather than the vehicle: WMI, the descriptor section,
     * the check digit, the model-year code and the plant. Everything after this is the serial.
     */
    const val DESCRIPTIVE_LENGTH = 11

    /** Where the check digit lives (0-based), and where the model year does. */
    private const val CHECK_INDEX = 8
    private const val YEAR_INDEX = 9

    /**
     * The 30-character model-year cycle, starting at 1980. `I`, `O`, `Q`, `U`, `Z` and `0` are not
     * used, which is exactly what makes the cycle 30 long rather than 36.
     */
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
    private const val YEAR_EPOCH = 1980

    /** Positional weights for the check digit; the check position itself weighs nothing. */
    private val WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** The transliteration table. `I`, `O` and `Q` are absent because they cannot appear in a VIN. */
    private val VALUES: Map<Char, Int> = buildMap {
        "0123456789".forEachIndexed { index, c -> put(c, index) }
        put('A', 1); put('B', 2); put('C', 3); put('D', 4); put('E', 5); put('F', 6); put('G', 7)
        put('H', 8); put('J', 1); put('K', 2); put('L', 3); put('M', 4); put('N', 5); put('P', 7)
        put('R', 9); put('S', 2); put('T', 3); put('U', 4); put('V', 5); put('W', 6); put('X', 7)
        put('Y', 8); put('Z', 9)
    }

    /** What a VIN can be wrong about, worst first. */
    enum class Problem(val message: String) {
        LENGTH("A VIN is 17 characters"),
        ILLEGAL_CHARACTER("A VIN never contains I, O or Q"),
        CHECK_DIGIT("The check digit doesn't match — worth a second look")
    }

    /** Upper case, with the spaces and hyphens people put in while reading one aloud removed. */
    fun normalise(raw: String): String =
        raw.filterNot { it == ' ' || it == '-' || it == '.' }.uppercase()

    /** The worst thing wrong with [vin], or null if it is clean. Blank is not a problem here. */
    fun problem(vin: String): Problem? {
        val value = normalise(vin)
        if (value.isEmpty()) return null
        if (value.length != LENGTH) return Problem.LENGTH
        if (value.any { it !in VALUES }) return Problem.ILLEGAL_CHARACTER
        return if (checkDigit(value) == value[CHECK_INDEX]) null else Problem.CHECK_DIGIT
    }

    /** True only for a VIN that is well-formed *and* self-consistent. */
    fun isValid(vin: String): Boolean = normalise(vin).length == LENGTH && problem(vin) == null

    /**
     * The check digit [vin] ought to carry, or null if it is not well-formed enough to say.
     * `10` is written `X`, which is the one place a VIN steps outside its own alphabet.
     */
    fun checkDigit(vin: String): Char? {
        val value = normalise(vin)
        if (value.length != LENGTH) return null
        var sum = 0
        value.forEachIndexed { index, c ->
            val digit = VALUES[c] ?: return null
            sum += digit * WEIGHTS[index]
        }
        val remainder = sum % 11
        return if (remainder == 10) 'X' else '0' + remainder
    }

    /**
     * The part of a VIN that describes the *model*, with the part that identifies *your vehicle*
     * replaced by wildcards — `1C4HJXDG5JW******`.
     *
     * This is the privacy rule of the whole decode feature, written as a function so it is a thing
     * that can be tested rather than a thing somebody remembered to do. A VIN's last six characters
     * are the serial: they are what appears on your title and your insurance, and they are what a
     * vehicle-history service keys off. They contribute **nothing** to a decode — NHTSA's vPIC
     * returns the same make, model, year, trim, engine, drive and transmission for the eleven
     * characters as for the seventeen, which was checked against the live API before this was
     * written.
     *
     * So the app sends the question *"what is a 2018 Wrangler Unlimited Sport 3.6 4x4?"* and never
     * the question *"what is this specific truck?"* — the same line Health draws between asking what
     * a medicine is and saying who takes it.
     *
     * Null when there is not enough of a VIN to be worth asking about.
     */
    fun decodeQuery(vin: String): String? {
        val value = normalise(vin)
        if (value.length < DESCRIPTIVE_LENGTH) return null
        return value.take(DESCRIPTIVE_LENGTH).padEnd(LENGTH, '*')
    }

    /** The manufacturer's identifier — the first three characters. Not decoded; there is no table. */
    fun wmi(vin: String): String? = normalise(vin).takeIf { it.length == LENGTH }?.take(3)

    /**
     * The model year the tenth character encodes, resolved against [currentYear].
     *
     * The code repeats every thirty years, so `T` is 1996 *and* 2026 and the character alone cannot
     * tell you which. The tie is broken the way a person would break it: take the most recent
     * occurrence that isn't in the future, allowing one year of lead because next year's models are
     * sold this year. A genuinely thirty-year-old car therefore reads as new — which is why this is
     * shown as "2026 (from the VIN)" beside the year you typed, and never quietly overwrites it.
     */
    fun modelYear(vin: String, currentYear: Int): Int? {
        val value = normalise(vin)
        if (value.length != LENGTH) return null
        val index = YEAR_CODES.indexOf(value[YEAR_INDEX])
        if (index < 0) return null
        val base = YEAR_EPOCH + index
        val cycles = (currentYear + 1 - base).floorDiv(YEAR_CODES.length)
        return base + cycles * YEAR_CODES.length
    }
}
