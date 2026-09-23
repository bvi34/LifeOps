package com.health.app.logic

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Every kind of data Health imports from Health Connect: what each is called, what it is grouped
 * under, what unit its number is in, and how a day of it adds up. Framework-free, so the screens'
 * sentences and the day totals are tested on the JVM; reading the records is `connect/`'s job.
 *
 * Each imported row has one headline number, [ConnectKind.unit] says what it is in, and it is
 * always stored in that unit: metres, kilograms, kilocalories, litres, °C, mmHg, mmol/L, watts,
 * metres per second. The display units the household chose for weights and temperatures are applied
 * when the number is shown, the same as for readings typed in by hand. Everything else a record
 * carries — sleep stages, heart-rate samples, the forty nutrients in a meal — is kept in the row's
 * detail, so nothing Health Connect held is thrown away on the way in.
 */
enum class ConnectCategory(val label: String) {
    ACTIVITY("Activity"),
    BODY("Body measurements"),
    VITALS("Vitals"),
    SLEEP("Sleep"),
    NUTRITION("Nutrition"),
    CYCLE("Cycle tracking"),
    WELLBEING("Wellbeing"),
    MEDICAL("Medical records")
}

/**
 * How one day's worth of a kind is summarised.
 *
 * [SUM] kinds are amounts — steps, distance, calories, water — that mean something added up over a
 * day. [LATEST] kinds are states — a weight, a resting heart rate — where the day's answer is the
 * last one measured, and adding two weights up is nonsense.
 */
enum class DayRollup { SUM, LATEST }

enum class ConnectKind(
    val key: String,
    val label: String,
    val category: ConnectCategory,
    /** The unit of the headline number, or null for kinds that are an observation, not a number. */
    val unit: String?,
    val rollup: DayRollup = DayRollup.LATEST
) {
    // Activity
    STEPS("steps", "Steps", ConnectCategory.ACTIVITY, "steps", DayRollup.SUM),
    DISTANCE("distance", "Distance", ConnectCategory.ACTIVITY, "m", DayRollup.SUM),
    ACTIVE_CALORIES("active_calories", "Active calories", ConnectCategory.ACTIVITY, "kcal", DayRollup.SUM),
    TOTAL_CALORIES("total_calories", "Total calories burned", ConnectCategory.ACTIVITY, "kcal", DayRollup.SUM),
    FLOORS_CLIMBED("floors_climbed", "Floors climbed", ConnectCategory.ACTIVITY, "floors", DayRollup.SUM),
    ELEVATION_GAINED("elevation_gained", "Elevation gained", ConnectCategory.ACTIVITY, "m", DayRollup.SUM),
    WHEELCHAIR_PUSHES("wheelchair_pushes", "Wheelchair pushes", ConnectCategory.ACTIVITY, "pushes", DayRollup.SUM),
    EXERCISE("exercise", "Exercise", ConnectCategory.ACTIVITY, "min", DayRollup.SUM),
    PLANNED_EXERCISE("planned_exercise", "Planned exercise", ConnectCategory.ACTIVITY, null),
    SPEED("speed", "Speed", ConnectCategory.ACTIVITY, "m/s"),
    POWER("power", "Power", ConnectCategory.ACTIVITY, "W"),
    STEPS_CADENCE("steps_cadence", "Step cadence", ConnectCategory.ACTIVITY, "steps/min"),
    CYCLING_CADENCE("cycling_cadence", "Cycling cadence", ConnectCategory.ACTIVITY, "rpm"),

    // Body measurements
    WEIGHT("weight", "Weight", ConnectCategory.BODY, "kg"),
    HEIGHT("height", "Height", ConnectCategory.BODY, "m"),
    BODY_FAT("body_fat", "Body fat", ConnectCategory.BODY, "%"),
    LEAN_BODY_MASS("lean_body_mass", "Lean body mass", ConnectCategory.BODY, "kg"),
    BONE_MASS("bone_mass", "Bone mass", ConnectCategory.BODY, "kg"),
    BODY_WATER_MASS("body_water_mass", "Body water mass", ConnectCategory.BODY, "kg"),
    BASAL_METABOLIC_RATE("basal_metabolic_rate", "Basal metabolic rate", ConnectCategory.BODY, "kcal/day"),

    // Vitals
    HEART_RATE("heart_rate", "Heart rate", ConnectCategory.VITALS, "bpm"),
    RESTING_HEART_RATE("resting_heart_rate", "Resting heart rate", ConnectCategory.VITALS, "bpm"),
    HEART_RATE_VARIABILITY("heart_rate_variability", "Heart rate variability", ConnectCategory.VITALS, "ms"),
    BLOOD_PRESSURE("blood_pressure", "Blood pressure", ConnectCategory.VITALS, "mmHg"),
    BLOOD_GLUCOSE("blood_glucose", "Blood glucose", ConnectCategory.VITALS, "mmol/L"),
    BODY_TEMPERATURE("body_temperature", "Body temperature", ConnectCategory.VITALS, "°C"),
    SKIN_TEMPERATURE("skin_temperature", "Skin temperature change", ConnectCategory.VITALS, "Δ°C"),
    OXYGEN_SATURATION("oxygen_saturation", "Oxygen saturation", ConnectCategory.VITALS, "%"),
    RESPIRATORY_RATE("respiratory_rate", "Breathing rate", ConnectCategory.VITALS, "breaths/min"),
    VO2_MAX("vo2_max", "VO₂ max", ConnectCategory.VITALS, "mL/kg/min"),

    // Sleep
    SLEEP("sleep", "Sleep", ConnectCategory.SLEEP, "h", DayRollup.SUM),

    // Nutrition
    NUTRITION("nutrition", "Food", ConnectCategory.NUTRITION, "kcal", DayRollup.SUM),
    HYDRATION("hydration", "Water", ConnectCategory.NUTRITION, "L", DayRollup.SUM),

    // Cycle tracking
    MENSTRUATION_PERIOD("menstruation_period", "Period", ConnectCategory.CYCLE, "days"),
    MENSTRUATION_FLOW("menstruation_flow", "Menstrual flow", ConnectCategory.CYCLE, null),
    INTERMENSTRUAL_BLEEDING("intermenstrual_bleeding", "Spotting", ConnectCategory.CYCLE, null),
    OVULATION_TEST("ovulation_test", "Ovulation test", ConnectCategory.CYCLE, null),
    CERVICAL_MUCUS("cervical_mucus", "Cervical mucus", ConnectCategory.CYCLE, null),
    BASAL_BODY_TEMPERATURE("basal_body_temperature", "Basal body temperature", ConnectCategory.CYCLE, "°C"),
    SEXUAL_ACTIVITY("sexual_activity", "Sexual activity", ConnectCategory.CYCLE, null),

    // Wellbeing
    MINDFULNESS("mindfulness", "Mindfulness", ConnectCategory.WELLBEING, "min", DayRollup.SUM),

    // Medical records — FHIR resources, kept as the source sent them.
    MEDICAL_VACCINES("medical_vaccines", "Vaccinations", ConnectCategory.MEDICAL, null),
    MEDICAL_ALLERGIES("medical_allergies", "Allergies and intolerances", ConnectCategory.MEDICAL, null),
    MEDICAL_CONDITIONS("medical_conditions", "Conditions", ConnectCategory.MEDICAL, null),
    MEDICAL_MEDICATIONS("medical_medications", "Medications", ConnectCategory.MEDICAL, null),
    MEDICAL_LAB_RESULTS("medical_lab_results", "Lab results", ConnectCategory.MEDICAL, null),
    MEDICAL_VITAL_SIGNS("medical_vital_signs", "Vital signs (clinical)", ConnectCategory.MEDICAL, null),
    MEDICAL_PROCEDURES("medical_procedures", "Procedures", ConnectCategory.MEDICAL, null),
    MEDICAL_VISITS("medical_visits", "Visits", ConnectCategory.MEDICAL, null),
    MEDICAL_PRACTITIONERS("medical_practitioners", "Practitioners", ConnectCategory.MEDICAL, null),
    MEDICAL_PERSONAL_DETAILS("medical_personal_details", "Personal details", ConnectCategory.MEDICAL, null),
    MEDICAL_PREGNANCY("medical_pregnancy", "Pregnancy", ConnectCategory.MEDICAL, null),
    MEDICAL_SOCIAL_HISTORY("medical_social_history", "Social history", ConnectCategory.MEDICAL, null);

    val isMedical: Boolean get() = category == ConnectCategory.MEDICAL

    companion object {
        fun fromKey(key: String): ConnectKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One imported record reduced to what a summary needs. The data layer's row carries more; this is
 * the shape [ConnectSummaries] works on so it can stay free of Room.
 */
data class ConnectPoint(
    val kind: ConnectKind,
    val startAt: Long,
    val endAt: Long?,
    val value: Double?,
    val secondaryValue: Double?,
    /** The app that wrote it to Health Connect, which is what de-duplication is decided by. */
    val source: String?
)

/** What one kind came to over one day. [value] is null for kinds with no number. */
data class DayFigure(val kind: ConnectKind, val value: Double?, val secondaryValue: Double?, val at: Long, val count: Int)

object ConnectSummaries {

    /**
     * What each kind came to on the day [dayStart] until [dayEnd].
     *
     * Summed kinds are summed **per source, and the largest source wins**. A phone and a watch both
     * count steps, both write them to Health Connect, and both sets arrive here; adding them
     * together would credit the household with a walk it took once as two. Health Connect's own
     * totals decide between overlapping sources with a priority list the user sets, which Health
     * cannot read. The largest single source is the conservative stand-in — never more than
     * somebody did, and usually what the better-placed device saw.
     *
     * A record that straddles the day's start (a night's sleep, begun before midnight) counts
     * towards the day it **ended** in — the morning a person wakes up is the morning they ask how
     * they slept.
     */
    fun day(points: List<ConnectPoint>, dayStart: Long, dayEnd: Long): List<DayFigure> =
        points
            .filter { belongsToDay(it, dayStart, dayEnd) }
            .groupBy { it.kind }
            .map { (kind, ofKind) -> figure(kind, ofKind) }
            .sortedBy { it.kind.ordinal }

    /** The most recent record of each kind, whatever day it fell on. */
    fun latest(points: List<ConnectPoint>): List<DayFigure> =
        points.groupBy { it.kind }
            .map { (kind, ofKind) ->
                val newest = ofKind.maxBy { it.endAt ?: it.startAt }
                DayFigure(kind, newest.value, newest.secondaryValue, newest.endAt ?: newest.startAt, ofKind.size)
            }
            .sortedBy { it.kind.ordinal }

    private fun belongsToDay(point: ConnectPoint, dayStart: Long, dayEnd: Long): Boolean {
        val anchor = point.endAt ?: point.startAt
        return anchor in dayStart until dayEnd
    }

    private fun figure(kind: ConnectKind, points: List<ConnectPoint>): DayFigure {
        val newest = points.maxBy { it.endAt ?: it.startAt }
        val at = newest.endAt ?: newest.startAt
        return when (kind.rollup) {
            DayRollup.LATEST -> DayFigure(kind, newest.value, newest.secondaryValue, at, points.size)
            DayRollup.SUM -> {
                val bySource = points.groupBy { it.source.orEmpty() }
                    .mapValues { (_, fromSource) -> fromSource.sumOf { it.value ?: 0.0 } }
                DayFigure(kind, bySource.values.maxOrNull(), null, at, points.size)
            }
        }
    }
}

/**
 * The sentence for one number of one kind, in the units the household reads in.
 *
 * [weightUnit] and [tempUnit] are the household's own choices from Health's settings. The other
 * units are fixed: distance in kilometres, water in litres, energy in kilocalories, sleep in hours
 * and minutes.
 */
object ConnectFormat {

    fun value(
        kind: ConnectKind,
        value: Double?,
        secondaryValue: Double?,
        weightUnit: WeightUnit = WeightUnit.KILOGRAMS,
        tempUnit: TempUnit = TempUnit.CELSIUS
    ): String {
        if (value == null) return "Recorded"
        return when (kind) {
            ConnectKind.STEPS, ConnectKind.WHEELCHAIR_PUSHES, ConnectKind.FLOORS_CLIMBED ->
                "${grouped(value)} ${kind.unit}"
            ConnectKind.DISTANCE -> if (value >= 1000) "${one(value / 1000)} km" else "${value.roundToLong()} m"
            ConnectKind.ELEVATION_GAINED -> "${value.roundToLong()} m"
            ConnectKind.ACTIVE_CALORIES, ConnectKind.TOTAL_CALORIES, ConnectKind.NUTRITION ->
                "${grouped(value)} kcal"
            ConnectKind.BASAL_METABOLIC_RATE -> "${grouped(value)} kcal/day"
            ConnectKind.EXERCISE, ConnectKind.MINDFULNESS -> duration(value * 60_000)
            ConnectKind.SLEEP -> duration(value * 3_600_000)
            ConnectKind.WEIGHT, ConnectKind.LEAN_BODY_MASS, ConnectKind.BONE_MASS, ConnectKind.BODY_WATER_MASS ->
                Weight.format(value, weightUnit)
            ConnectKind.HEIGHT -> "${value.times(100).roundToLong()} cm"
            ConnectKind.BODY_FAT, ConnectKind.OXYGEN_SATURATION -> "${one(value)}%"
            ConnectKind.HEART_RATE, ConnectKind.RESTING_HEART_RATE -> "${value.roundToLong()} bpm"
            ConnectKind.HEART_RATE_VARIABILITY -> "${value.roundToLong()} ms"
            ConnectKind.BLOOD_PRESSURE ->
                "${value.roundToLong()}/${secondaryValue?.roundToLong() ?: "–"} mmHg"
            ConnectKind.BLOOD_GLUCOSE -> "${one(value)} mmol/L"
            ConnectKind.BODY_TEMPERATURE, ConnectKind.BASAL_BODY_TEMPERATURE -> Temperature.format(value, tempUnit)
            ConnectKind.SKIN_TEMPERATURE -> {
                val delta = if (tempUnit == TempUnit.FAHRENHEIT) value * 9 / 5 else value
                val sign = if (delta >= 0) "+" else ""
                "$sign${String.format(Locale.US, "%.2f", delta)} ${tempUnit.symbol}"
            }
            ConnectKind.RESPIRATORY_RATE -> "${one(value)} breaths/min"
            ConnectKind.VO2_MAX -> "${one(value)} mL/kg/min"
            ConnectKind.SPEED -> "${one(value * 3.6)} km/h"
            ConnectKind.POWER -> "${value.roundToLong()} W"
            ConnectKind.STEPS_CADENCE -> "${value.roundToLong()} steps/min"
            ConnectKind.CYCLING_CADENCE -> "${value.roundToLong()} rpm"
            ConnectKind.HYDRATION -> "${String.format(Locale.US, "%.2f", value)} L"
            ConnectKind.MENSTRUATION_PERIOD -> {
                val days = value.roundToLong()
                if (days == 1L) "1 day" else "$days days"
            }
            else -> kind.unit?.let { "${one(value)} $it" } ?: "Recorded"
        }
    }

    /** "7 h 32 min", "45 min", "0 min". */
    fun duration(millis: Double): String {
        val minutes = (millis / 60_000).roundToLong().coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    private fun grouped(value: Double): String = String.format(Locale.US, "%,d", value.roundToLong())

    private fun one(value: Double): String {
        val text = String.format(Locale.US, "%.1f", value)
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}
