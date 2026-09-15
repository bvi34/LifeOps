package com.health.app.data.model

import com.health.app.logic.TempSite
import com.health.app.logic.VitalRange
import com.health.app.logic.Vitals

/**
 * What was measured and what was observed: readings and symptoms.
 */

enum class ReadingType(
    val key: String,
    val label: String,
    val unit: String,
    val range: VitalRange,
    /** The second number's bounds, for the one reading that has two. */
    val secondaryRange: VitalRange? = null
) {
    TEMPERATURE("temperature", "Temperature", "°C", Vitals.TEMPERATURE),
    HEART_RATE("heart_rate", "Heart rate", "bpm", Vitals.HEART_RATE),
    BLOOD_PRESSURE("blood_pressure", "Blood pressure", "mmHg", Vitals.SYSTOLIC, Vitals.DIASTOLIC),
    OXYGEN("oxygen", "Oxygen (SpO₂)", "%", Vitals.OXYGEN),
    RESPIRATORY_RATE("respiratory_rate", "Breathing rate", "breaths/min", Vitals.RESPIRATORY_RATE),
    WEIGHT("weight", "Weight", "kg", Vitals.WEIGHT);

    companion object {
        fun fromKey(key: String?): ReadingType =
            entries.firstOrNull { it.key == key } ?: TEMPERATURE
    }
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
