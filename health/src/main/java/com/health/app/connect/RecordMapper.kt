package com.health.app.connect

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import com.health.app.data.model.ImportedRecord
import com.health.app.logic.ConnectKind
import com.health.app.logic.TempSite
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns a Health Connect record into Health's [ImportedRecord]: one headline number in the unit
 * [ConnectKind] says, and everything else the record carried in its detail.
 *
 * One branch per record type, written out rather than read by reflection: the suite is shrunk by
 * R8, which renames exactly the getters reflection would look for, and a mapper that silently
 * produced empty records in release builds would be worse than none.
 *
 * Enumerations — sleep stages, flow, meal type — are written into the detail as words rather than
 * Health Connect's integers. The library has tables for this, but they are internal to it; the
 * integers are public API and cannot change, so the tables here are built from them.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object RecordMapper {

    /** [record] as Health keeps it, or null for a record type Health doesn't import. */
    fun map(record: Record): ImportedRecord? = when (record) {
        is StepsRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset, ConnectKind.STEPS, record.count.toDouble()
        )
        is DistanceRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.DISTANCE, record.distance.inMeters
        )
        is ActiveCaloriesBurnedRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.ACTIVE_CALORIES, record.energy.inKilocalories
        )
        is TotalCaloriesBurnedRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.TOTAL_CALORIES, record.energy.inKilocalories
        )
        is FloorsClimbedRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset, ConnectKind.FLOORS_CLIMBED, record.floors
        )
        is ElevationGainedRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.ELEVATION_GAINED, record.elevation.inMeters
        )
        is WheelchairPushesRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.WHEELCHAIR_PUSHES, record.count.toDouble()
        )
        is ExerciseSessionRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.EXERCISE, minutes(record.startTime, record.endTime),
            detail = mapOf(
                "exerciseType" to exerciseType(record.exerciseType),
                "title" to record.title,
                "notes" to record.notes,
                "segments" to record.segments.map { segment ->
                    mapOf(
                        "start" to segment.startTime.toEpochMilli(),
                        "end" to segment.endTime.toEpochMilli(),
                        "type" to segment.segmentType,
                        "repetitions" to segment.repetitions
                    )
                },
                "laps" to record.laps.map { lap ->
                    mapOf(
                        "start" to lap.startTime.toEpochMilli(),
                        "end" to lap.endTime.toEpochMilli(),
                        "meters" to lap.length?.inMeters
                    )
                },
                "plannedSessionId" to record.plannedExerciseSessionId
            )
        )
        is PlannedExerciseSessionRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset, ConnectKind.PLANNED_EXERCISE, null,
            detail = mapOf(
                "exerciseType" to exerciseType(record.exerciseType),
                "title" to record.title,
                "notes" to record.notes,
                "blocks" to record.blocks.size,
                "completedSessionId" to record.completedExerciseSessionId
            )
        )
        is SpeedRecord -> series(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.SPEED, record.samples.map { it.time to it.speed.inMetersPerSecond }
        )
        is PowerRecord -> series(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.POWER, record.samples.map { it.time to it.power.inWatts }
        )
        is StepsCadenceRecord -> series(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.STEPS_CADENCE, record.samples.map { it.time to it.rate }
        )
        is CyclingPedalingCadenceRecord ->
            series(
                record, record.startTime, record.endTime, record.endZoneOffset,
                ConnectKind.CYCLING_CADENCE, record.samples.map { it.time to it.revolutionsPerMinute }
            )
        is HeartRateRecord ->
            series(
                record, record.startTime, record.endTime, record.endZoneOffset,
                ConnectKind.HEART_RATE, record.samples.map { it.time to it.beatsPerMinute.toDouble() }
            )

        is WeightRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.WEIGHT, record.weight.inKilograms
        )
        is HeightRecord -> instant(record, record.time, record.zoneOffset, ConnectKind.HEIGHT, record.height.inMeters)
        is BodyFatRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BODY_FAT, record.percentage.value
        )
        is LeanBodyMassRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.LEAN_BODY_MASS, record.mass.inKilograms
        )
        is BoneMassRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BONE_MASS, record.mass.inKilograms
        )
        is BodyWaterMassRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BODY_WATER_MASS, record.mass.inKilograms
        )
        is BasalMetabolicRateRecord ->
            instant(
                record, record.time, record.zoneOffset,
                ConnectKind.BASAL_METABOLIC_RATE, record.basalMetabolicRate.inKilocaloriesPerDay
            )

        is RestingHeartRateRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.RESTING_HEART_RATE, record.beatsPerMinute.toDouble()
        )
        is HeartRateVariabilityRmssdRecord ->
            instant(
                record, record.time, record.zoneOffset,
                ConnectKind.HEART_RATE_VARIABILITY, record.heartRateVariabilityMillis
            )
        is BloodPressureRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BLOOD_PRESSURE, record.systolic.inMillimetersOfMercury,
            secondary = record.diastolic.inMillimetersOfMercury,
            detail = mapOf(
                "bodyPosition" to label(record.bodyPosition, BODY_POSITIONS),
                "location" to label(record.measurementLocation, BLOOD_PRESSURE_LOCATIONS)
            )
        )
        is BloodGlucoseRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BLOOD_GLUCOSE, record.level.inMillimolesPerLiter,
            detail = mapOf(
                "specimen" to label(record.specimenSource, SPECIMEN_SOURCES),
                "meal" to label(record.mealType, MEAL_TYPES),
                "relationToMeal" to label(record.relationToMeal, RELATIONS_TO_MEAL)
            )
        )
        is BodyTemperatureRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BODY_TEMPERATURE, record.temperature.inCelsius,
            detail = temperatureDetail(record.measurementLocation)
        )
        is BasalBodyTemperatureRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.BASAL_BODY_TEMPERATURE, record.temperature.inCelsius,
            detail = temperatureDetail(record.measurementLocation)
        )
        is SkinTemperatureRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset, ConnectKind.SKIN_TEMPERATURE,
            record.deltas.map { it.delta.inCelsius }.takeIf { it.isNotEmpty() }?.average(),
            detail = mapOf(
                "baselineC" to record.baseline?.inCelsius,
                "location" to label(record.measurementLocation, SKIN_LOCATIONS),
                "deltas" to record.deltas.map { listOf(it.time.toEpochMilli(), it.delta.inCelsius) }
            )
        )
        is OxygenSaturationRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.OXYGEN_SATURATION, record.percentage.value
        )
        is RespiratoryRateRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.RESPIRATORY_RATE, record.rate
        )
        is Vo2MaxRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.VO2_MAX, record.vo2MillilitersPerMinuteKilogram,
            detail = mapOf("method" to label(record.measurementMethod, VO2_METHODS))
        )

        is SleepSessionRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.SLEEP, SleepMath.hoursAsleep(record),
            detail = mapOf(
                "title" to record.title,
                "notes" to record.notes,
                "stages" to record.stages.map { stage ->
                    mapOf(
                        "start" to stage.startTime.toEpochMilli(),
                        "end" to stage.endTime.toEpochMilli(),
                        "stage" to label(stage.stage, SLEEP_STAGES)
                    )
                }
            )
        )

        is NutritionRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.NUTRITION, record.energy?.inKilocalories,
            detail = buildMap {
                put("name", record.name)
                put("meal", label(record.mealType, MEAL_TYPES))
                record.energyFromFat?.let { put("energyFromFatKcal", it.inKilocalories) }
                put("grams", nutrients(record))
            }
        )
        is HydrationRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.HYDRATION, record.volume.inLiters
        )

        is MenstruationPeriodRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset, ConnectKind.MENSTRUATION_PERIOD,
            Duration.between(record.startTime, record.endTime).toHours() / 24.0
        )
        is MenstruationFlowRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.MENSTRUATION_FLOW, null,
            detail = mapOf("flow" to label(record.flow, FLOWS))
        )
        is IntermenstrualBleedingRecord -> instant(
            record, record.time, record.zoneOffset, ConnectKind.INTERMENSTRUAL_BLEEDING, null
        )
        is OvulationTestRecord -> instant(record, record.time, record.zoneOffset, ConnectKind.OVULATION_TEST, null,
            detail = mapOf("result" to label(record.result, OVULATION_RESULTS))
        )
        is CervicalMucusRecord -> instant(record, record.time, record.zoneOffset, ConnectKind.CERVICAL_MUCUS, null,
            detail = mapOf(
                "appearance" to label(record.appearance, MUCUS_APPEARANCES),
                "sensation" to label(record.sensation, MUCUS_SENSATIONS)
            )
        )
        is SexualActivityRecord -> instant(record, record.time, record.zoneOffset, ConnectKind.SEXUAL_ACTIVITY, null,
            detail = mapOf("protection" to label(record.protectionUsed, PROTECTION))
        )
        is MindfulnessSessionRecord -> interval(
            record, record.startTime, record.endTime, record.endZoneOffset,
            ConnectKind.MINDFULNESS, minutes(record.startTime, record.endTime),
            detail = mapOf(
                "type" to label(record.mindfulnessSessionType, MINDFULNESS_TYPES),
                "title" to record.title,
                "notes" to record.notes
            )
        )
        else -> null
    }

    private fun instant(
        record: Record,
        time: Instant,
        offset: ZoneOffset?,
        kind: ConnectKind,
        value: Double?,
        secondary: Double? = null,
        detail: Map<String, Any?> = emptyMap()
    ) = ImportedRecord(
        id = record.metadata.id,
        kind = kind,
        startAt = time.toEpochMilli(),
        endAt = null,
        zoneOffsetSeconds = offset?.totalSeconds,
        value = value,
        secondaryValue = secondary,
        detail = withOrigin(record, detail),
        source = record.metadata.dataOrigin.packageName,
        device = device(record),
        modifiedAt = record.metadata.lastModifiedTime.toEpochMilli()
    )

    private fun interval(
        record: Record,
        start: Instant,
        end: Instant,
        offset: ZoneOffset?,
        kind: ConnectKind,
        value: Double?,
        detail: Map<String, Any?> = emptyMap()
    ) = ImportedRecord(
        id = record.metadata.id,
        kind = kind,
        startAt = start.toEpochMilli(),
        endAt = end.toEpochMilli(),
        zoneOffsetSeconds = offset?.totalSeconds,
        value = value,
        detail = withOrigin(record, detail),
        source = record.metadata.dataOrigin.packageName,
        device = device(record),
        modifiedAt = record.metadata.lastModifiedTime.toEpochMilli()
    )

    /**
     * A series — heart rate, speed, power, cadence — as its average, with the lowest, the highest
     * and every sample kept in the detail as `[epochMillis, value]` pairs.
     */
    private fun series(
        record: Record,
        start: Instant,
        end: Instant,
        offset: ZoneOffset?,
        kind: ConnectKind,
        samples: List<Pair<Instant, Double>>
    ): ImportedRecord {
        val values = samples.map { it.second }
        return interval(
            record, start, end, offset, kind, values.takeIf { it.isNotEmpty() }?.average(),
            detail = mapOf(
                "min" to values.minOrNull(),
                "max" to values.maxOrNull(),
                "samples" to samples.map { (time, value) -> listOf(time.toEpochMilli(), value) }
            )
        )
    }

    /** How the record was made, which every kind carries, added to the kind's own detail. */
    private fun withOrigin(record: Record, detail: Map<String, Any?>): Map<String, Any?> =
        detail.filterValues { it != null } + ("recording" to label(record.metadata.recordingMethod, RECORDING_METHODS))

    private fun device(record: Record): String? =
        record.metadata.device?.let { device ->
            listOfNotNull(device.manufacturer, device.model).joinToString(" ").ifBlank { null }
        }

    private fun minutes(start: Instant, end: Instant): Double = Duration.between(start, end).toMillis() / 60_000.0

    private fun temperatureDetail(location: Int): Map<String, Any?> = mapOf(
        "location" to label(location, TEMPERATURE_LOCATIONS),
        // The same measuring site in the words the fever rules use, so a mirrored reading is judged
        // for where it was taken. Null where Health has no matching site (finger, wrist, toe).
        "site" to when (location) {
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH -> TempSite.ORAL.key
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM -> TempSite.RECTAL.key
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR -> TempSite.EAR.key
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD,
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY -> TempSite.TEMPORAL.key
            BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT -> TempSite.AXILLARY.key
            else -> null
        }
    )

    /** Every nutrient the meal named, in grams. Those it didn't name are left out, not zero. */
    private fun nutrients(record: NutritionRecord): Map<String, Double> {
        val all: List<Pair<String, Mass?>> = listOf(
            "protein" to record.protein,
            "totalCarbohydrate" to record.totalCarbohydrate,
            "sugar" to record.sugar,
            "dietaryFiber" to record.dietaryFiber,
            "totalFat" to record.totalFat,
            "saturatedFat" to record.saturatedFat,
            "unsaturatedFat" to record.unsaturatedFat,
            "monounsaturatedFat" to record.monounsaturatedFat,
            "polyunsaturatedFat" to record.polyunsaturatedFat,
            "transFat" to record.transFat,
            "cholesterol" to record.cholesterol,
            "sodium" to record.sodium,
            "potassium" to record.potassium,
            "chloride" to record.chloride,
            "calcium" to record.calcium,
            "iron" to record.iron,
            "magnesium" to record.magnesium,
            "phosphorus" to record.phosphorus,
            "zinc" to record.zinc,
            "copper" to record.copper,
            "manganese" to record.manganese,
            "selenium" to record.selenium,
            "chromium" to record.chromium,
            "molybdenum" to record.molybdenum,
            "iodine" to record.iodine,
            "caffeine" to record.caffeine,
            "vitaminA" to record.vitaminA,
            "vitaminB6" to record.vitaminB6,
            "vitaminB12" to record.vitaminB12,
            "vitaminC" to record.vitaminC,
            "vitaminD" to record.vitaminD,
            "vitaminE" to record.vitaminE,
            "vitaminK" to record.vitaminK,
            "thiamin" to record.thiamin,
            "riboflavin" to record.riboflavin,
            "niacin" to record.niacin,
            "pantothenicAcid" to record.pantothenicAcid,
            "biotin" to record.biotin,
            "folate" to record.folate,
            "folicAcid" to record.folicAcid
        )
        return all.mapNotNull { (name, mass) -> mass?.let { name to it.inGrams } }.toMap()
    }

    private fun label(value: Int, labels: Map<Int, String>): String = labels[value] ?: "unknown"

    fun exerciseType(type: Int): String = EXERCISE_TYPES[type] ?: "Exercise"

    val SLEEP_STAGES = mapOf(
        SleepSessionRecord.STAGE_TYPE_UNKNOWN to "unknown",
        SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
        SleepSessionRecord.STAGE_TYPE_SLEEPING to "sleeping",
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out of bed",
        SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
        SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
        SleepSessionRecord.STAGE_TYPE_REM to "REM",
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake in bed"
    )

    private val FLOWS = mapOf(
        MenstruationFlowRecord.FLOW_LIGHT to "light",
        MenstruationFlowRecord.FLOW_MEDIUM to "medium",
        MenstruationFlowRecord.FLOW_HEAVY to "heavy"
    )

    private val OVULATION_RESULTS = mapOf(
        OvulationTestRecord.RESULT_INCONCLUSIVE to "inconclusive",
        OvulationTestRecord.RESULT_POSITIVE to "positive",
        OvulationTestRecord.RESULT_HIGH to "high",
        OvulationTestRecord.RESULT_NEGATIVE to "negative"
    )

    private val MUCUS_APPEARANCES = mapOf(
        CervicalMucusRecord.APPEARANCE_DRY to "dry",
        CervicalMucusRecord.APPEARANCE_STICKY to "sticky",
        CervicalMucusRecord.APPEARANCE_CREAMY to "creamy",
        CervicalMucusRecord.APPEARANCE_WATERY to "watery",
        CervicalMucusRecord.APPEARANCE_EGG_WHITE to "egg white",
        CervicalMucusRecord.APPEARANCE_UNUSUAL to "unusual"
    )

    private val MUCUS_SENSATIONS = mapOf(
        CervicalMucusRecord.SENSATION_LIGHT to "light",
        CervicalMucusRecord.SENSATION_MEDIUM to "medium",
        CervicalMucusRecord.SENSATION_HEAVY to "heavy"
    )

    private val PROTECTION = mapOf(
        SexualActivityRecord.PROTECTION_USED_PROTECTED to "protected",
        SexualActivityRecord.PROTECTION_USED_UNPROTECTED to "unprotected"
    )

    private val TEMPERATURE_LOCATIONS = mapOf(
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_ARMPIT to "armpit",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER to "finger",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FOREHEAD to "forehead",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_MOUTH to "mouth",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_RECTUM to "rectum",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TEMPORAL_ARTERY to "temporal artery",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_TOE to "toe",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR to "ear",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_WRIST to "wrist",
        BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_VAGINA to "vagina"
    )

    private val SKIN_LOCATIONS = mapOf(
        SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER to "finger",
        SkinTemperatureRecord.MEASUREMENT_LOCATION_TOE to "toe",
        SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST to "wrist"
    )

    private val BLOOD_PRESSURE_LOCATIONS = mapOf(
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST to "left wrist",
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST to "right wrist",
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM to "left upper arm",
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM to "right upper arm"
    )

    private val BODY_POSITIONS = mapOf(
        BloodPressureRecord.BODY_POSITION_STANDING_UP to "standing",
        BloodPressureRecord.BODY_POSITION_SITTING_DOWN to "sitting",
        BloodPressureRecord.BODY_POSITION_LYING_DOWN to "lying down",
        BloodPressureRecord.BODY_POSITION_RECLINING to "reclining"
    )

    private val SPECIMEN_SOURCES = mapOf(
        BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID to "interstitial fluid",
        BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD to "capillary blood",
        BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA to "plasma",
        BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM to "serum",
        BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS to "tears",
        BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD to "whole blood"
    )

    private val RELATIONS_TO_MEAL = mapOf(
        BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL to "general",
        BloodGlucoseRecord.RELATION_TO_MEAL_FASTING to "fasting",
        BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL to "before a meal",
        BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL to "after a meal"
    )

    private val MEAL_TYPES = mapOf(
        MealType.MEAL_TYPE_BREAKFAST to "breakfast",
        MealType.MEAL_TYPE_LUNCH to "lunch",
        MealType.MEAL_TYPE_DINNER to "dinner",
        MealType.MEAL_TYPE_SNACK to "snack"
    )

    private val VO2_METHODS = mapOf(
        Vo2MaxRecord.MEASUREMENT_METHOD_OTHER to "other",
        Vo2MaxRecord.MEASUREMENT_METHOD_METABOLIC_CART to "metabolic cart",
        Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO to "heart rate ratio",
        Vo2MaxRecord.MEASUREMENT_METHOD_COOPER_TEST to "Cooper test",
        Vo2MaxRecord.MEASUREMENT_METHOD_MULTISTAGE_FITNESS_TEST to "multistage fitness test",
        Vo2MaxRecord.MEASUREMENT_METHOD_ROCKPORT_FITNESS_TEST to "Rockport fitness test"
    )

    private val MINDFULNESS_TYPES = mapOf(
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION to "meditation",
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING to "breathing",
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MUSIC to "music",
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MOVEMENT to "movement",
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNGUIDED to "unguided"
    )

    private val RECORDING_METHODS = mapOf(
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED to "actively recorded",
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED to "automatically recorded",
        Metadata.RECORDING_METHOD_MANUAL_ENTRY to "entered by hand"
    )

    private val EXERCISE_TYPES: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Other workout",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Baseball",
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Biking",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Biking stationary",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot camp",
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "Boxing",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "Cricket",
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "Exercise class",
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "Fencing",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "Football american",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "Football australian",
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "Frisbee disc",
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "Guided breathing",
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Gymnastics",
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "Handball",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "High intensity interval training",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "Ice hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "Ice skating",
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "Paddling",
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "Paragliding",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "Racquetball",
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "Rock climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "Roller hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Rowing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "Rugby",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Running treadmill",
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "Sailing",
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "Scuba diving",
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "Skating",
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "Skiing",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "Snowshoeing",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "Softball",
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stair climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair climbing machine",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength training",
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Stretching",
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surfing",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swimming open water",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Swimming pool",
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "Table tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "Water polo",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "Wheelchair",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
    )
}

/** Sleep arithmetic, kept apart from the mapping so the rule is easy to find. */
object SleepMath {

    private val notAsleep = setOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
    )

    /**
     * Hours actually asleep. With stages, the stages that are sleep added up — time lying awake is
     * not sleep, even though it is in bed. Without stages, the session's length: the writer only
     * said "asleep from here to here".
     */
    fun hoursAsleep(record: SleepSessionRecord): Double {
        val millis = if (record.stages.isEmpty()) {
            Duration.between(record.startTime, record.endTime).toMillis()
        } else {
            record.stages.filterNot { it.stage in notAsleep }
                .sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
        }
        return millis / 3_600_000.0
    }
}
