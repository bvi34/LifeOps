package com.health.app.logic

/**
 * What counts as a believable measurement, per kind, framework-free.
 *
 * Health already refused an impossible temperature and an impossible weight, each inside its own
 * parser, and refused nothing at all for the other four: a heart rate, a blood pressure, an oxygen
 * saturation and a breathing rate were whatever the number field accepted. That is not a small gap.
 * "92" typed into the oxygen field as "920", a decimal point missed out of a pulse, a systolic typed
 * into the diastolic box — each of those saves cleanly, charts, is read back to a doctor, and is
 * handed to Advisor as a fact about somebody's body.
 *
 * **These bounds catch typing, not patients.** They are deliberately far wider than anything a
 * clinician would call normal, because the job here is to reject numbers no living person produces,
 * never to argue with a reading somebody actually took. A resting pulse of 38 in an athlete and one
 * of 190 in a toddler with a fever are both real, and both go in. `Fever` is where Health says what
 * a number *means*; this is only where it says the number is a number.
 *
 * Temperature and weight keep their own parsers, because both convert from whatever unit the
 * household reads in before there is anything to bound — but both take their bounds from here, so
 * there is exactly one place that says what a believable reading is.
 */

/**
 * The bounds one kind of measurement falls in, in its canonical unit, and the sentence to say when
 * a typed value doesn't.
 *
 * The sentence lives with the bounds because the two are the same decision: a rule that rejects a
 * number owes the person who typed it an explanation of what was wrong with it, and a generic
 * "invalid" leaves them to guess whether the app disliked the value, the units or the comma.
 */
data class VitalRange(val min: Double, val max: Double, val complaint: String) {

    operator fun contains(value: Double): Boolean = value in min..max

    /**
     * Read a typed number and reject it unless it is believable.
     *
     * Tolerates a comma decimal separator and surrounding spaces, like the other two parsers: a
     * keyboard that gives "36,8" is a keyboard setting, not a mistake, and an app that refuses it
     * teaches people their reading is wrong when it is the app that is.
     */
    fun parse(text: String): Double? {
        val raw = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return raw.takeIf { it in this }
    }
}

object Vitals {

    /** °C. A body below 25 or above 45 is not a reading, it is a typo — or a broken thermometer. */
    val TEMPERATURE = VitalRange(25.0, 45.0, "That isn't a body temperature — check the number.")

    /** kg. From a newborn to the heaviest person recorded, with room on both sides. */
    val WEIGHT = VitalRange(0.2, 500.0, "That isn't a weight Health can read — check the number.")

    /** bpm. Wide on purpose: a trained adult at rest and a feverish toddler are both in here. */
    val HEART_RATE = VitalRange(20.0, 300.0, "That isn't a pulse — check the number.")

    /** mmHg, the top number. */
    val SYSTOLIC = VitalRange(40.0, 300.0, "That isn't a systolic pressure — check the number.")

    /**
     * mmHg, the bottom number.
     *
     * Bounded separately from [SYSTOLIC] rather than sharing one range, because the commonest blood
     * pressure mistake is the two numbers swapped, and a single range that admits both is a range
     * that cannot notice.
     */
    val DIASTOLIC = VitalRange(20.0, 200.0, "That isn't a diastolic pressure — check the number.")

    /** %. There is no more than all of it, and a home reading below 50 is a mistyped one. */
    val OXYGEN = VitalRange(50.0, 100.0, "Oxygen is a percentage — 100 is as high as it goes.")

    /** breaths/min. A sleeping adult at the bottom, a newborn in distress nowhere near the top. */
    val RESPIRATORY_RATE = VitalRange(4.0, 120.0, "That isn't a breathing rate — check the number.")
}
