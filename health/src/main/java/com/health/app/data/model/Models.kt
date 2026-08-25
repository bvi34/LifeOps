package com.health.app.data.model

import com.health.app.logic.Age
import com.health.app.logic.CareLevel
import com.health.app.logic.DoseWindow
import com.health.app.logic.FeverAssessment
import com.health.app.logic.TempSite

/**
 * What the screens work in. These are the entity rows with their string columns resolved into the
 * enums `logic/` actually reasons about — the mapping happens once, in the repository, rather than
 * being re-guessed by each screen.
 */

/** The kinds of measurement Health records. Each carries the canonical unit its value is stored in. */
enum class ReadingType(val key: String, val label: String, val unit: String) {
    TEMPERATURE("temperature", "Temperature", "°C"),
    HEART_RATE("heart_rate", "Heart rate", "bpm"),
    BLOOD_PRESSURE("blood_pressure", "Blood pressure", "mmHg"),
    OXYGEN("oxygen", "Oxygen (SpO₂)", "%"),
    RESPIRATORY_RATE("respiratory_rate", "Breathing rate", "breaths/min"),
    WEIGHT("weight", "Weight", "kg");

    companion object {
        fun fromKey(key: String?): ReadingType =
            entries.firstOrNull { it.key == key } ?: TEMPERATURE
    }
}

/** What a care-log entry is about. Free text carries the detail; this is only for grouping. */
enum class CareKind(val key: String, val label: String) {
    NOTE("note", "Note"),
    FLUIDS("fluids", "Fluids"),
    REST("rest", "Rest / sleep"),
    APPOINTMENT("appointment", "Doctor / appointment"),
    TEST("test", "Test result");

    companion object {
        fun fromKey(key: String?): CareKind = entries.firstOrNull { it.key == key } ?: NOTE
    }
}

data class Profile(
    val id: String,
    val name: String,
    val relationship: String?,
    val birthDate: String?,
    val colorArgb: Long,
    val baselineTempC: Double?,
    val notes: String?,
    val sortOrder: Int,
    val archived: Boolean
) {
    fun ageMonthsAt(nowMillis: Long): Int? = Age.monthsAt(birthDate, nowMillis)
    fun ageLabelAt(nowMillis: Long): String? = Age.describe(birthDate, nowMillis)
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"
}

data class Reading(
    val id: String,
    val profileId: String,
    val episodeId: String?,
    val type: ReadingType,
    val value: Double,
    val secondaryValue: Double?,
    val site: TempSite?,
    val takenAt: Long,
    val note: String?
)

data class Symptom(
    val id: String,
    val profileId: String,
    val episodeId: String?,
    val name: String,
    val severity: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?
) {
    val isActive: Boolean get() = endedAt == null
}

data class Medication(
    val id: String,
    val profileId: String,
    val name: String,
    val strength: String?,
    val form: String?,
    val doseAmount: Double?,
    val doseUnit: String,
    val minIntervalHours: Double?,
    val maxDosesPer24h: Int?,
    val maxAmountPer24h: Double?,
    val note: String?,
    val active: Boolean
)

data class Dose(
    val id: String,
    val profileId: String,
    val medicationId: String?,
    val medicationName: String,
    val amount: Double,
    val unit: String,
    val takenAt: Long,
    val note: String?,
    val episodeId: String?
)

data class Episode(
    val id: String,
    val profileId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?
) {
    val isOpen: Boolean get() = endedAt == null
}

data class CareNote(
    val id: String,
    val profileId: String,
    val episodeId: String?,
    val kind: CareKind,
    val text: String,
    val at: Long
)

/** A medicine paired with the answer to "can I give it yet?" — see `logic/DoseSchedule`. */
data class MedicationStatus(val medication: Medication, val window: DoseWindow)

/**
 * Everything one person's headline needs, assembled once: their latest temperature with its verdict,
 * the illness they're in the middle of (if any), what's still bothering them, and which medicines
 * are due. [careLevel] is the highest call any of it makes, so a screen can colour one badge rather
 * than re-deriving the worst case per card.
 */
data class ProfileSnapshot(
    val profile: Profile,
    val latestTemperature: Reading?,
    val temperatureAssessment: FeverAssessment?,
    val openEpisode: Episode?,
    val activeSymptoms: List<Symptom>,
    val medications: List<MedicationStatus>,
    val careLevel: CareLevel
) {
    val readyMedications: List<MedicationStatus> get() = medications.filter { it.window.isReady }
}
