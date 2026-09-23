package com.health.app.connect

import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
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
import androidx.health.connect.client.records.MedicalResource
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
import com.health.app.logic.ConnectKind
import kotlin.reflect.KClass

/**
 * Which Health Connect type each [ConnectKind] is read from, and the permission that reads it.
 *
 * Every permission named here is also declared in Health's manifest — Health Connect only offers a
 * permission the app declared, and the two lists have to agree. `ConnectTypesTest` checks that
 * every kind is covered exactly once.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class, ExperimentalPersonalHealthRecordApi::class)
object ConnectTypes {

    /** The ordinary record types: one Health Connect class per kind. */
    val RECORDS: Map<ConnectKind, KClass<out Record>> = mapOf(
        ConnectKind.STEPS to StepsRecord::class,
        ConnectKind.DISTANCE to DistanceRecord::class,
        ConnectKind.ACTIVE_CALORIES to ActiveCaloriesBurnedRecord::class,
        ConnectKind.TOTAL_CALORIES to TotalCaloriesBurnedRecord::class,
        ConnectKind.FLOORS_CLIMBED to FloorsClimbedRecord::class,
        ConnectKind.ELEVATION_GAINED to ElevationGainedRecord::class,
        ConnectKind.WHEELCHAIR_PUSHES to WheelchairPushesRecord::class,
        ConnectKind.EXERCISE to ExerciseSessionRecord::class,
        ConnectKind.PLANNED_EXERCISE to PlannedExerciseSessionRecord::class,
        ConnectKind.SPEED to SpeedRecord::class,
        ConnectKind.POWER to PowerRecord::class,
        ConnectKind.STEPS_CADENCE to StepsCadenceRecord::class,
        ConnectKind.CYCLING_CADENCE to CyclingPedalingCadenceRecord::class,
        ConnectKind.WEIGHT to WeightRecord::class,
        ConnectKind.HEIGHT to HeightRecord::class,
        ConnectKind.BODY_FAT to BodyFatRecord::class,
        ConnectKind.LEAN_BODY_MASS to LeanBodyMassRecord::class,
        ConnectKind.BONE_MASS to BoneMassRecord::class,
        ConnectKind.BODY_WATER_MASS to BodyWaterMassRecord::class,
        ConnectKind.BASAL_METABOLIC_RATE to BasalMetabolicRateRecord::class,
        ConnectKind.HEART_RATE to HeartRateRecord::class,
        ConnectKind.RESTING_HEART_RATE to RestingHeartRateRecord::class,
        ConnectKind.HEART_RATE_VARIABILITY to HeartRateVariabilityRmssdRecord::class,
        ConnectKind.BLOOD_PRESSURE to BloodPressureRecord::class,
        ConnectKind.BLOOD_GLUCOSE to BloodGlucoseRecord::class,
        ConnectKind.BODY_TEMPERATURE to BodyTemperatureRecord::class,
        ConnectKind.SKIN_TEMPERATURE to SkinTemperatureRecord::class,
        ConnectKind.OXYGEN_SATURATION to OxygenSaturationRecord::class,
        ConnectKind.RESPIRATORY_RATE to RespiratoryRateRecord::class,
        ConnectKind.VO2_MAX to Vo2MaxRecord::class,
        ConnectKind.SLEEP to SleepSessionRecord::class,
        ConnectKind.NUTRITION to NutritionRecord::class,
        ConnectKind.HYDRATION to HydrationRecord::class,
        ConnectKind.MENSTRUATION_PERIOD to MenstruationPeriodRecord::class,
        ConnectKind.MENSTRUATION_FLOW to MenstruationFlowRecord::class,
        ConnectKind.INTERMENSTRUAL_BLEEDING to IntermenstrualBleedingRecord::class,
        ConnectKind.OVULATION_TEST to OvulationTestRecord::class,
        ConnectKind.CERVICAL_MUCUS to CervicalMucusRecord::class,
        ConnectKind.BASAL_BODY_TEMPERATURE to BasalBodyTemperatureRecord::class,
        ConnectKind.SEXUAL_ACTIVITY to SexualActivityRecord::class,
        ConnectKind.MINDFULNESS to MindfulnessSessionRecord::class
    )

    /** A medical kind's FHIR resource type in Health Connect, and the permission that reads it. */
    data class MedicalType(val resourceType: Int, val permission: String)

    val MEDICAL: Map<ConnectKind, MedicalType> = mapOf(
        ConnectKind.MEDICAL_VACCINES to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VACCINES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VACCINES
        ),
        ConnectKind.MEDICAL_ALLERGIES to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_ALLERGIES_INTOLERANCES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES
        ),
        ConnectKind.MEDICAL_CONDITIONS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_CONDITIONS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_CONDITIONS
        ),
        ConnectKind.MEDICAL_MEDICATIONS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_MEDICATIONS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_MEDICATIONS
        ),
        ConnectKind.MEDICAL_LAB_RESULTS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_LABORATORY_RESULTS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_LABORATORY_RESULTS
        ),
        ConnectKind.MEDICAL_VITAL_SIGNS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VITAL_SIGNS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VITAL_SIGNS
        ),
        ConnectKind.MEDICAL_PROCEDURES to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PROCEDURES,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PROCEDURES
        ),
        ConnectKind.MEDICAL_VISITS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_VISITS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_VISITS
        ),
        ConnectKind.MEDICAL_PRACTITIONERS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PRACTITIONER_DETAILS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PRACTITIONER_DETAILS
        ),
        ConnectKind.MEDICAL_PERSONAL_DETAILS to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PERSONAL_DETAILS,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PERSONAL_DETAILS
        ),
        ConnectKind.MEDICAL_PREGNANCY to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_PREGNANCY,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_PREGNANCY
        ),
        ConnectKind.MEDICAL_SOCIAL_HISTORY to MedicalType(
            MedicalResource.MEDICAL_RESOURCE_TYPE_SOCIAL_HISTORY,
            HealthPermission.PERMISSION_READ_MEDICAL_DATA_SOCIAL_HISTORY
        )
    )

    /** Reading beyond the last thirty days — without it, Health Connect answers a month at most. */
    const val HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Reading while Health is not on screen, which the scheduled import needs. */
    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun permissionFor(kind: ConnectKind): String =
        MEDICAL[kind]?.permission ?: HealthPermission.getReadPermission(RECORDS.getValue(kind))

    /** Everything Health asks for: every kind, plus history and background reading. */
    val ALL_PERMISSIONS: Set<String> by lazy {
        ConnectKind.entries.map { permissionFor(it) }.toSet() + HISTORY + BACKGROUND
    }
}
