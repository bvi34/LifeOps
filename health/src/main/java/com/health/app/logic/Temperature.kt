package com.health.app.logic

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body temperature, framework-free.
 *
 * Health stores every reading in **Celsius**, always, and converts on the way in and out. A store
 * that keeps whatever unit was typed is a store you can't compare, average or chart without
 * carrying the unit through every calculation — and the one place it eventually gets forgotten is
 * the place that decides whether someone has a fever.
 */
enum class TempUnit(val key: String, val symbol: String) {
    CELSIUS("c", "°C"),
    FAHRENHEIT("f", "°F");

    companion object {
        fun fromKey(key: String): TempUnit = entries.firstOrNull { it.key == key } ?: CELSIUS
    }
}

/**
 * Where the reading was taken. This is not decoration: the same body reads differently by site, and
 * a 37.8 under the arm is not the same news as a 37.8 in the ear. Each site carries the offset that
 * converts it to the **oral-equivalent** scale every threshold in [Fever] is expressed on, using the
 * conventional clinical adjustments (rectal/ear run ~0.5/0.3 °C above oral, armpit ~0.5 °C below).
 */
enum class TempSite(val key: String, val label: String, val toOralOffsetC: Double) {
    ORAL("oral", "Mouth", 0.0),
    RECTAL("rectal", "Rectal", -0.5),
    EAR("ear", "Ear", -0.3),
    TEMPORAL("temporal", "Forehead", -0.3),
    AXILLARY("axillary", "Armpit", 0.5);

    companion object {
        fun fromKey(key: String?): TempSite = entries.firstOrNull { it.key == key } ?: ORAL
    }
}

object Temperature {

    /** Physically plausible bounds for a body reading — shared with every other measurement's, in
     * `logic/Vitals`, so there is one place that says what a believable reading is. */
    private val PLAUSIBLE = Vitals.TEMPERATURE

    fun toCelsius(value: Double, unit: TempUnit): Double =
        if (unit == TempUnit.CELSIUS) value else (value - 32.0) * 5.0 / 9.0

    fun fromCelsius(celsius: Double, unit: TempUnit): Double =
        if (unit == TempUnit.CELSIUS) celsius else celsius * 9.0 / 5.0 + 32.0

    /** A difference (not a point) converted between scales: 0.5 °C is 0.9 °F, not 32.9 °F. */
    fun deltaFromCelsius(deltaC: Double, unit: TempUnit): Double =
        if (unit == TempUnit.CELSIUS) deltaC else deltaC * 9.0 / 5.0

    /**
     * Read what someone typed. Tolerates a stray unit suffix, a comma decimal separator and
     * surrounding spaces, and rejects anything that isn't a believable body temperature — a typed
     * "986" for 98.6 is caught here rather than charted as a heat-death event.
     *
     * Returns the value in **Celsius**, or null if it isn't usable.
     */
    fun parseToCelsius(text: String, unit: TempUnit): Double? {
        val cleaned = text.trim()
            .removeSuffix("°C").removeSuffix("°F").removeSuffix("C").removeSuffix("F")
            .trim()
            .replace(',', '.')
        val raw = cleaned.toDoubleOrNull() ?: return null
        val celsius = toCelsius(raw, unit)
        return celsius.takeIf { it in PLAUSIBLE }
    }

    /** "38.4 °C" / "101.1 °F" — one decimal, which is all a home thermometer is honest to. */
    fun format(celsius: Double, unit: TempUnit): String =
        "${round1(fromCelsius(celsius, unit))} ${unit.symbol}"

    /** The same value without the unit symbol, for text fields that are already labelled. */
    fun formatBare(celsius: Double, unit: TempUnit): String = round1(fromCelsius(celsius, unit)).toString()

    /** A signed change, e.g. "+0.6 °C" — used for trends, where the sign is the whole message. */
    fun formatDelta(deltaC: Double, unit: TempUnit): String {
        val value = round1(deltaFromCelsius(deltaC, unit))
        val sign = if (value > 0) "+" else ""
        return "$sign$value ${unit.symbol}"
    }

    /** Round half-up to one decimal, keeping -0.0 out of the UI. */
    fun round1(value: Double): Double {
        val rounded = (value * 10.0).roundToInt() / 10.0
        return if (abs(rounded) < 0.05) 0.0 else rounded
    }
}
