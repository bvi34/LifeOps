package com.health.app.logic

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body weight, framework-free.
 *
 * The same bargain temperature makes (see [Temperature]): Health stores every weight in
 * **kilograms**, always, and converts on the way in and out. A store that keeps whatever unit was
 * typed is a store you can't compare or chart — and a weight is read back to a doctor to size a
 * dose, which is the last number in the app that should be ambiguous about its unit.
 */
enum class WeightUnit(val key: String, val symbol: String, val label: String) {
    KILOGRAMS("kg", "kg", "Kilograms (kg)"),
    POUNDS("lb", "lb", "Pounds (lb)");

    companion object {
        fun fromKey(key: String): WeightUnit = entries.firstOrNull { it.key == key } ?: KILOGRAMS
    }
}

object Weight {

    /** The international avoirdupois pound, so a converted number matches the scale in the bathroom. */
    private const val POUNDS_PER_KILOGRAM = 2.2046226218487757

    /** Plausible bounds for a human weight — kept with every other measurement's in `logic/Vitals`. */
    private val PLAUSIBLE = Vitals.WEIGHT

    fun toKilograms(value: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.KILOGRAMS) value else value / POUNDS_PER_KILOGRAM

    fun fromKilograms(kilograms: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.KILOGRAMS) kilograms else kilograms * POUNDS_PER_KILOGRAM

    /**
     * A difference (not a point) converted between scales.
     *
     * Unlike Celsius to Fahrenheit this is a pure scale with no offset, so a change converts exactly
     * the way a reading does — it gets its own name anyway, so that a caller reaching for "how do I
     * convert a difference" finds the right answer here rather than guessing that the point
     * conversion happens to be safe.
     */
    fun deltaFromKilograms(deltaKg: Double, unit: WeightUnit): Double = fromKilograms(deltaKg, unit)

    /**
     * Read what someone typed. Tolerates a stray unit suffix, a comma decimal separator and
     * surrounding spaces, and rejects anything that isn't a believable body weight — a "705" typed
     * for 70.5 is caught here rather than charted.
     *
     * Returns the value in **kilograms**, or null if it isn't usable.
     */
    fun parseToKilograms(text: String, unit: WeightUnit): Double? {
        val cleaned = text.trim().lowercase()
            .removeSuffix("kgs").removeSuffix("kg")
            .removeSuffix("lbs").removeSuffix("lb")
            .trim()
            .replace(',', '.')
        val raw = cleaned.toDoubleOrNull() ?: return null
        val kilograms = toKilograms(raw, unit)
        return kilograms.takeIf { it in PLAUSIBLE }
    }

    /** "70.5 kg" / "155.4 lb" — one decimal, which is all a bathroom scale is honest to. */
    fun format(kilograms: Double, unit: WeightUnit): String =
        "${round1(fromKilograms(kilograms, unit))} ${unit.symbol}"

    /** The same value without the unit symbol, for text fields that are already labelled. */
    fun formatBare(kilograms: Double, unit: WeightUnit): String =
        round1(fromKilograms(kilograms, unit)).toString()

    /** A signed change, e.g. "-1.2 kg" — used for trends, where the sign is the whole message. */
    fun formatDelta(deltaKg: Double, unit: WeightUnit): String {
        val value = round1(deltaFromKilograms(deltaKg, unit))
        val sign = if (value > 0) "+" else ""
        return "$sign$value ${unit.symbol}"
    }

    /** Round half-up to one decimal, keeping -0.0 out of the UI. */
    fun round1(value: Double): Double {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (abs(rounded) < 0.05) 0.0 else rounded
    }
}
