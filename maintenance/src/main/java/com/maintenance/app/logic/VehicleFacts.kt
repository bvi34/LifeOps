package com.maintenance.app.logic

/**
 * What a VIN says about a vehicle, once it has been decoded and normalised.
 *
 * This is the join between the outside world and this app's own vocabulary: the decoder
 * (`data/net/VpicClient`) produces one of these, and everything downstream — picking a schedule,
 * filling in make and model, asking about recalls — reads only this. So the 154 fields NHTSA
 * returns never leak into the rest of the app, and swapping the decoder for another one is a change
 * to one file.
 *
 * Every field is nullable because every field genuinely can be missing: vPIC answers what the
 * manufacturer filed with it, and older or imported vehicles are patchy.
 */
data class VehicleFacts(
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val trim: String? = null,
    val bodyClass: String? = null,
    val driveType: String? = null,
    val engineCylinders: Int? = null,
    val displacementLitres: Double? = null,
    val fuel: String? = null,
    val transmission: String? = null,
    val manufacturer: String? = null,
    val plant: String? = null,
    /**
     * What the decoder complained about, if anything. vPIC still answers a VIN whose check digit
     * doesn't compute — it just says so — which matches this app's own line on VINs: a bad check
     * digit is worth mentioning and is not a refusal.
     */
    val note: String? = null
) {
    val isEmpty: Boolean get() = make == null && model == null && year == null

    /** "2018 Jeep Wrangler" — for a heading, and for the fields the decode offers to fill in. */
    val descriptor: String
        get() = listOfNotNull(year?.toString(), make?.titleCase(), model).joinToString(" ")

    /** "3.6L V6" — the engine as one line, from the two numbers vPIC files it under. */
    val engine: String?
        get() = displacementLitres?.let { litres ->
            val cylinders = engineCylinders?.let { " V$it" }.orEmpty()
            "${trimZero(litres)}L$cylinders"
        }

    /**
     * "4WD" — vPIC writes the drive type as "4WD/4-Wheel Drive", which is the same answer twice.
     */
    val drive: String?
        get() = driveType?.substringBefore('/')?.trim()?.takeIf { it.isNotBlank() }

    /** "3.6L V6 · 4WD · Unlimited Sport" — the line under it. */
    val detail: String
        get() = listOfNotNull(engine, drive, trim?.takeIf { it.isNotBlank() }).joinToString(" · ")

    private fun trimZero(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}

/** vPIC shouts its makes ("JEEP"); the app doesn't. */
internal fun String.titleCase(): String =
    lowercase().split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
