package com.health.app.logic

/**
 * How worrying a reading is, on the oral-equivalent scale. The bands are the ordinary clinical ones;
 * the point of naming them is that every screen, summary and Advisor answer says the same thing
 * about the same number.
 */
enum class FeverBand(val label: String) {
    LOW("Below normal"),
    NORMAL("Normal"),
    ELEVATED("Slightly raised"),
    FEVER("Fever"),
    HIGH_FEVER("High fever"),
    VERY_HIGH("Very high")
}

/** What the reading asks of you. Ordered: a later entry outranks an earlier one. */
enum class CareLevel(val label: String) {
    ROUTINE("Nothing to do"),
    MONITOR("Keep an eye on it"),
    CALL_DOCTOR("Worth calling a doctor"),
    SEEK_CARE_NOW("Get medical help now")
}

/**
 * One reading, assessed.
 *
 * [oralEquivalentC] is the measured value adjusted for [TempSite], and is the number [band] was
 * decided on — it is surfaced so a screen can explain why an armpit 37.6 was called a fever.
 */
data class FeverAssessment(
    val measuredC: Double,
    val site: TempSite,
    val oralEquivalentC: Double,
    val band: FeverBand,
    val careLevel: CareLevel,
    val reasons: List<String>
) {
    val isFever: Boolean get() = band >= FeverBand.FEVER
}

/**
 * Reading-level fever assessment, framework-free and age-aware.
 *
 * **This is not medical advice and does not pretend to be.** It is a consistent reading of
 * widely-published home-care thresholds, written down once so the app doesn't improvise a different
 * opinion on each screen. The red flags are deliberately conservative — the cost of "call someone"
 * when you needn't have is an hour; the cost of the other mistake isn't measured in hours. Every
 * surface that shows this must say so, and the app does.
 */
object Fever {

    /** Oral-equivalent °C at which we start calling it a fever. */
    const val FEVER_C = 38.0

    private const val ELEVATED_C = 37.5
    private const val HIGH_FEVER_C = 39.0
    private const val VERY_HIGH_C = 40.0
    private const val HYPOTHERMIA_C = 35.0

    /** Under this age, *any* fever is an immediate-care matter rather than a home-care one. */
    private const val NEWBORN_MONTHS = 3

    /** 3–6 months: a high fever, not merely a fever, is the escalation point. */
    private const val INFANT_MONTHS = 6

    /**
     * Assess one reading. [measuredC] is what the thermometer said (in Celsius, as stored), [site]
     * is where, and [ageMonths] is the person's age in months when known — pass null for an adult
     * profile with no birth date and the adult rules apply.
     */
    fun assess(measuredC: Double, site: TempSite, ageMonths: Int? = null): FeverAssessment {
        val oral = Temperature.round1(measuredC + site.toOralOffsetC)
        val band = bandFor(oral)
        val reasons = mutableListOf<String>()
        var care = when (band) {
            FeverBand.LOW -> CareLevel.CALL_DOCTOR
            FeverBand.NORMAL -> CareLevel.ROUTINE
            FeverBand.ELEVATED -> CareLevel.MONITOR
            FeverBand.FEVER -> CareLevel.MONITOR
            FeverBand.HIGH_FEVER -> CareLevel.CALL_DOCTOR
            FeverBand.VERY_HIGH -> CareLevel.SEEK_CARE_NOW
        }

        if (site != TempSite.ORAL && band >= FeverBand.ELEVATED) {
            reasons += "${site.label} reading adjusted to ${Temperature.round1(oral)} °C oral-equivalent."
        }

        when (band) {
            FeverBand.LOW -> reasons += "Below normal body temperature — recheck, and get advice if it holds."
            FeverBand.VERY_HIGH -> reasons += "At or above 40 °C — treat as urgent at any age."
            else -> Unit
        }

        // The age red flags are checked against the *higher* of the measured and oral-equivalent
        // values. The published infant thresholds ("38.0 °C in a baby under three months") are
        // written against rectal readings, which run above oral — adjusting one down to the other
        // and then testing it would quietly under-call the exact case these rules exist for. Taking
        // whichever reads higher errs toward calling someone, which is the direction to err in here.
        val flagBand = bandFor(maxOf(measuredC, oral))
        if (ageMonths != null && flagBand >= FeverBand.FEVER) {
            when {
                ageMonths < NEWBORN_MONTHS -> {
                    care = CareLevel.SEEK_CARE_NOW
                    reasons += "Under 3 months old: any fever needs medical assessment straight away."
                }
                ageMonths < INFANT_MONTHS && flagBand >= FeverBand.HIGH_FEVER -> {
                    care = maxOf(care, CareLevel.SEEK_CARE_NOW)
                    reasons += "Under 6 months old with a high fever: get medical advice now."
                }
                ageMonths < INFANT_MONTHS -> {
                    care = maxOf(care, CareLevel.CALL_DOCTOR)
                    reasons += "Under 6 months old: worth calling about any fever."
                }
            }
        }

        return FeverAssessment(
            measuredC = measuredC,
            site = site,
            oralEquivalentC = oral,
            band = band,
            careLevel = care,
            reasons = reasons.toList()
        )
    }

    /** The band an already-adjusted (oral-equivalent) reading falls in. */
    fun bandFor(oralEquivalentC: Double): FeverBand = when {
        oralEquivalentC < HYPOTHERMIA_C -> FeverBand.LOW
        oralEquivalentC < ELEVATED_C -> FeverBand.NORMAL
        oralEquivalentC < FEVER_C -> FeverBand.ELEVATED
        oralEquivalentC < HIGH_FEVER_C -> FeverBand.FEVER
        oralEquivalentC < VERY_HIGH_C -> FeverBand.HIGH_FEVER
        else -> FeverBand.VERY_HIGH
    }

    /** Convenience: is this measured reading, at this site, a fever? */
    fun isFever(measuredC: Double, site: TempSite): Boolean =
        bandFor(measuredC + site.toOralOffsetC) >= FeverBand.FEVER

    /** The standing disclaimer, in one place so every surface says it the same way. */
    const val DISCLAIMER: String =
        "Health tracks what you record — it isn't medical advice. When something worries you, " +
            "call a doctor or your local emergency number."
}
