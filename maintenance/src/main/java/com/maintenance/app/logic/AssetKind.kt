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

    HOME(
        key = "home",
        label = "Home",
        plural = "Homes",
        meter = null,
        attributes = listOf(
            AssetAttributeSpec("address", "Address", AttributeInput.MULTILINE),
            AssetAttributeSpec("yearBuilt", "Year built", AttributeInput.NUMBER, AttributeCheck.YEAR),
            AssetAttributeSpec("squareFeet", "Living area (sq ft)", AttributeInput.NUMBER, AttributeCheck.WHOLE_NUMBER),
            AssetAttributeSpec("lotSize", "Lot size (acres)", AttributeInput.NUMBER, AttributeCheck.DECIMAL),
            AssetAttributeSpec(
                "parcelNumber", "Parcel number (APN)", AttributeInput.TEXT,
                hint = "As it reads on the tax bill"
            )
        )
    ),

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
            AssetAttributeSpec("licensePlate", "License plate", AttributeInput.TEXT),
            AssetAttributeSpec("trim", "Trim", AttributeInput.TEXT),
            AssetAttributeSpec("color", "Color", AttributeInput.TEXT)
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

/** How a kind-specific field is typed in. */
enum class AttributeInput { TEXT, NUMBER, MULTILINE }

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
    val hint: String? = null
)

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
        return when (spec.check) {
            AttributeCheck.VIN -> Vin.normalise(trimmed)
            else -> trimmed
        }
    }

    /**
     * What is wrong with [raw] for [spec], as a sentence to put under the field — or null when
     * there is nothing to say. Blank is never a problem.
     */
    fun problem(spec: AssetAttributeSpec, raw: String): String? {
        val value = normalise(spec, raw)
        if (value.isBlank()) return null
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
