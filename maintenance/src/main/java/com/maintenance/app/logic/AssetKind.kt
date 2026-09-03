package com.maintenance.app.logic

/**
 * What kind of thing an asset is, and the handful of facts that kind is *identified* by.
 *
 * This is the file that decides what Maintenance asks you for. A house has a parcel number and no
 * odometer; a car has a VIN, a plate and a mileage that climbs; a furnace has a serial number and
 * sits in a room. Those are not five apps and they are not five tables — they are one asset with a
 * different set of labels on it, which is why the kind-specific fields are *declared here as data*
 * and stored as rows in `asset_attributes` rather than as columns nobody else's kind can use.
 *
 * The alternative — a `vin` column, an `address` column, a `serialNumber` column, all null for
 * every asset that isn't the one kind that uses them — is the shape this deliberately avoids.
 * Adding "Boat" later is then authoring: one entry below, and every screen already knows how to
 * draw, validate and store its fields.
 *
 * What stays a real column on the asset itself is what *every* kind has: a name, a make and model,
 * a year, what it cost and what it is worth. Those are asked of a mower and a mortgage alike.
 */
enum class AssetKind(
    val key: String,
    val label: String,
    val plural: String,
    /** What this kind's usage is counted in, when it is counted at all. */
    val meter: MeterUnit?,
    val attributes: List<AssetAttributeSpec>
) {

    /**
     * A house has no VIN, so nothing outside this app can be asked what it is — see
     * `logic/HomeFacts` for why that is a privacy fact rather than a missing feature. What it has
     * instead is an address with a ZIP in it, a year, and a household that knows perfectly well
     * what the place is made of and what is bolted to it.
     *
     * So the two fields upkeep actually reads are **pickers, not prose**. "Type of home" is one
     * choice and changes what the house structurally owes — a manufactured home has piers to
     * re-level and skirting to check, and no condo owner cleans the gutters. "What it has" is a
     * multiple choice, and each thing ticked is a schedule the house picks up: a septic tank, a
     * well, gas, solar, a pool. Announcing what you have is the whole of the input, and the
     * schedules follow from it.
     */
    HOME(
        key = "home",
        label = "Home",
        plural = "Homes",
        meter = null,
        attributes = listOf(
            AssetAttributeSpec(
                "address", "Address", AttributeInput.MULTILINE,
                hint = "The ZIP is the part upkeep reads — it is what says whether the taps need draining"
            ),
            AssetAttributeSpec(
                "structure", "Type of home", AttributeInput.CHOICE,
                hint = "What the building is, which decides what it structurally owes",
                options = HomeStructure.entries.map { AssetAttributeOption(it.key, it.label, it.detail) }
            ),
            AssetAttributeSpec("yearBuilt", "Year built", AttributeInput.NUMBER, AttributeCheck.YEAR),
            AssetAttributeSpec("squareFeet", "Living area (sq ft)", AttributeInput.NUMBER, AttributeCheck.WHOLE_NUMBER),
            AssetAttributeSpec("lotSize", "Lot size (acres)", AttributeInput.NUMBER, AttributeCheck.DECIMAL),
            AssetAttributeSpec(
                "features", "What it has", AttributeInput.CHOICES,
                hint = "Each one ticked brings its own schedule",
                options = HomeFeature.entries.map { AssetAttributeOption(it.key, it.label, it.detail) }
            ),
            AssetAttributeSpec(
                "parcelNumber", "Parcel number (APN)", AttributeInput.TEXT,
                hint = "As it reads on the tax bill"
            )
        )
    ),

    /**
     * The fields are, deliberately, the whole of what the paperwork and the door jamb say — and the
     * first six of them are exactly what a VIN decode comes back with, so `applyVehicleFacts` can
     * fill them in rather than leaving them for somebody to copy off a screen by hand.
     *
     * The last two are the pair nobody can ever remember and everybody needs at a counter: what size
     * the tyres are and what oil goes in it.
     */
    VEHICLE(
        key = "vehicle",
        label = "Vehicle",
        plural = "Vehicles",
        meter = MeterUnit.MILES,
        attributes = listOf(
            AssetAttributeSpec(
                "vin", "VIN", AttributeInput.TEXT, AttributeCheck.VIN,
                hint = "17 characters, no I, O or Q"
            ),
            AssetAttributeSpec("trim", "Trim", AttributeInput.TEXT),
            AssetAttributeSpec("bodyStyle", "Body style", AttributeInput.TEXT, hint = "Sedan, pickup, SUV, …"),
            AssetAttributeSpec("engine", "Engine", AttributeInput.TEXT, hint = "3.6L V6"),
            AssetAttributeSpec("fuel", "Fuel", AttributeInput.TEXT, hint = "Gasoline, diesel, electric, …"),
            AssetAttributeSpec("transmission", "Transmission", AttributeInput.TEXT, hint = "Automatic, manual, CVT"),
            AssetAttributeSpec("driveType", "Drivetrain", AttributeInput.TEXT, hint = "FWD, RWD, AWD, 4WD"),
            AssetAttributeSpec("color", "Color", AttributeInput.TEXT),
            AssetAttributeSpec("licensePlate", "License plate", AttributeInput.TEXT),
            AssetAttributeSpec("plateState", "Registered in", AttributeInput.TEXT, hint = "The state or province on the plate"),
            AssetAttributeSpec("tireSize", "Tire size", AttributeInput.TEXT, hint = "As it reads on the sidewall — 245/70R17"),
            AssetAttributeSpec("oilSpec", "Oil / fluid spec", AttributeInput.TEXT, hint = "5W-30 full synthetic")
        )
    ),

    APPLIANCE(
        key = "appliance",
        label = "Appliance",
        plural = "Appliances",
        meter = null,
        attributes = listOf(
            AssetAttributeSpec("serialNumber", "Serial number", AttributeInput.TEXT),
            AssetAttributeSpec("modelNumber", "Model number", AttributeInput.TEXT),
            AssetAttributeSpec("location", "Where it lives", AttributeInput.TEXT, hint = "Basement, kitchen, …"),
            AssetAttributeSpec("filterSize", "Filter / consumable size", AttributeInput.TEXT)
        )
    ),

    EQUIPMENT(
        key = "equipment",
        label = "Equipment",
        plural = "Equipment",
        meter = MeterUnit.HOURS,
        attributes = listOf(
            AssetAttributeSpec("serialNumber", "Serial number", AttributeInput.TEXT),
            AssetAttributeSpec("location", "Where it lives", AttributeInput.TEXT),
            AssetAttributeSpec("fuel", "Fuel / oil", AttributeInput.TEXT, hint = "87 octane, SAE 30, …")
        )
    ),

    OTHER(
        key = "other",
        label = "Other",
        plural = "Other",
        meter = null,
        attributes = listOf(
            AssetAttributeSpec("serialNumber", "Serial number", AttributeInput.TEXT),
            AssetAttributeSpec("location", "Where it lives", AttributeInput.TEXT)
        )
    );

    /** The attribute [key]'s spec, when this kind asks for it. */
    fun spec(key: String): AssetAttributeSpec? = attributes.firstOrNull { it.key == key }

    companion object {
        /** Unknown keys resolve to [OTHER] rather than throwing: a restored row must still open. */
        fun of(key: String?): AssetKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** What a meter counts. Only kinds that actually wear one have a unit; the rest have `null`. */
enum class MeterUnit(val key: String, val reading: String, val short: String, val noun: String) {
    MILES("miles", "Odometer", "mi", "miles"),
    HOURS("hours", "Hour meter", "hr", "hours");

    /** "12,400 mi" — the way a reading is written everywhere it is shown. */
    fun format(value: Long): String = "${group(value)} $short"

    companion object {
        fun of(key: String?): MeterUnit? = entries.firstOrNull { it.key == key }

        /** 12400 → "12,400". Meter readings are read at a glance; ungrouped digits are not. */
        fun group(value: Long): String {
            val negative = value < 0
            val body = kotlin.math.abs(value).toString().reversed().chunked(3).joinToString(",").reversed()
            return if (negative) "-$body" else body
        }
    }
}

/**
 * How a kind-specific field is filled in.
 *
 * The last two are not typed at all: they are picked from a list the spec carries, which is what a
 * field wants when the set of right answers is short, closed and known to the app. "Septic" spelled
 * three ways is three answers to a question that has one, and a schedule that only appears when
 * somebody happens to write the word the parser was hoping for is a schedule that mostly does not
 * appear.
 */
enum class AttributeInput {
    TEXT,
    NUMBER,
    MULTILINE,

    /** One of the spec's options, stored as that option's key. */
    CHOICE,

    /** Any number of the spec's options, stored as their keys joined by commas. */
    CHOICES
}

/**
 * One option on a [AttributeInput.CHOICE] or [AttributeInput.CHOICES] field.
 *
 * [key] is what is stored and is permanent: it is what a schedule is matched on, so renaming one
 * silently unticks it on every asset already carrying it. [label] is what a person reads and may be
 * reworded freely.
 */
data class AssetAttributeOption(val key: String, val label: String, val detail: String? = null)

/** What, if anything, is checked about the value that comes back. */
enum class AttributeCheck { NONE, VIN, YEAR, WHOLE_NUMBER, DECIMAL }

/**
 * One kind-specific field: what it is called, how it is typed, and what would make it wrong.
 *
 * The check is an enum rather than a lambda so a spec stays comparable data — two kinds asking for
 * the same field really are equal, and the whole catalogue can be walked in a test.
 */
data class AssetAttributeSpec(
    val key: String,
    val label: String,
    val input: AttributeInput = AttributeInput.TEXT,
    val check: AttributeCheck = AttributeCheck.NONE,
    val hint: String? = null,
    /** What a picker picks from. Empty on every field that is typed rather than chosen. */
    val options: List<AssetAttributeOption> = emptyList()
) {
    val isPicker: Boolean get() = input == AttributeInput.CHOICE || input == AttributeInput.CHOICES

    fun option(key: String): AssetAttributeOption? = options.firstOrNull { it.key == key }
}

/**
 * The values themselves: tidied on the way in, and complained about only when they are genuinely
 * wrong.
 *
 * Two rules hold everywhere here. **Blank is always allowed** — half the fields on a mower are
 * unknown on the day you add it, and an app that refuses the row until you go and read the sticker
 * is an app that never gets the mower typed in at all. And **a complaint is never a refusal**: see
 * [problem], which returns something to *show*, while the caller stores what the user typed either
 * way. A VIN whose check digit disagrees is still the VIN on your title.
 */
object AssetAttributes {

    /** Trim, and uppercase the fields that are conventionally written in capitals. */
    fun normalise(spec: AssetAttributeSpec, raw: String): String {
        val trimmed = raw.trim()
        if (spec.isPicker) return store(chosen(spec, trimmed))
        return when (spec.check) {
            AttributeCheck.VIN -> Vin.normalise(trimmed)
            else -> trimmed
        }
    }

    /**
     * The option keys held in a picker's stored value.
     *
     * **An unrecognised key is kept, not dropped.** A row can arrive from a backup written by a
     * later build, or from a field that used to be free text, and quietly deleting what this build
     * does not understand is how a restore loses data it was trusted with. What this build does not
     * recognise it simply does not act on.
     *
     * A single [AttributeInput.CHOICE] is the same shape holding one key, so both read the same way
     * and neither has to know which it is.
     */
    fun chosen(spec: AssetAttributeSpec, raw: String?): List<String> {
        if (raw.isNullOrBlank() || !spec.isPicker) return emptyList()
        val keys = raw.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (spec.input == AttributeInput.CHOICE) keys.take(1) else keys
    }

    /** The way a picker's keys are written into the one column they share with every other field. */
    fun store(keys: List<String>): String = keys.filter { it.isNotBlank() }.distinct().joinToString(SEPARATOR)

    /** Whether [key] is currently ticked. */
    fun isChosen(spec: AssetAttributeSpec, raw: String?, key: String): Boolean = key in chosen(spec, raw)

    /**
     * The stored value with [key] ticked or unticked — the whole of what a picker's UI has to do.
     *
     * On a single choice, ticking replaces and ticking the ticked one clears it: a field that could
     * be set but never unset is a field somebody has to delete the asset to correct.
     */
    fun toggle(spec: AssetAttributeSpec, raw: String?, key: String): String {
        val current = chosen(spec, raw)
        return when {
            spec.input == AttributeInput.CHOICE -> store(if (key in current) emptyList() else listOf(key))
            key in current -> store(current - key)
            else -> store(current + key)
        }
    }

    /**
     * What a picker's value reads as — "Septic system · Private well · Solar panels".
     *
     * Options are listed in the spec's own order rather than the order they were ticked, so the same
     * set of answers always reads the same way. A key with no option is shown verbatim, for the same
     * reason [chosen] keeps it.
     */
    fun display(spec: AssetAttributeSpec, raw: String?): String {
        if (!spec.isPicker) return raw.orEmpty()
        val keys = chosen(spec, raw)
        val known = spec.options.filter { it.key in keys }.map { it.label }
        val unknown = keys.filter { spec.option(it) == null }
        return (known + unknown).joinToString(" · ")
    }

    private const val SEPARATOR = ","

    /**
     * What is wrong with [raw] for [spec], as a sentence to put under the field — or null when
     * there is nothing to say. Blank is never a problem.
     */
    fun problem(spec: AssetAttributeSpec, raw: String): String? {
        val value = normalise(spec, raw)
        if (value.isBlank()) return null
        // A picker cannot be typed wrong, and a key this build does not know is a row from another
        // build rather than a mistake somebody made.
        if (spec.isPicker) return null
        return when (spec.check) {
            AttributeCheck.NONE -> null
            AttributeCheck.VIN -> Vin.problem(value)?.message
            AttributeCheck.YEAR -> {
                val year = value.toIntOrNull()
                when {
                    year == null -> "A year, like 1998"
                    year < 1500 || year > 2200 -> "That year looks wrong"
                    else -> null
                }
            }
            AttributeCheck.WHOLE_NUMBER ->
                if (value.replace(",", "").toLongOrNull() == null) "A whole number" else null
            AttributeCheck.DECIMAL ->
                if (value.replace(",", "").toDoubleOrNull() == null) "A number" else null
        }
    }
}
