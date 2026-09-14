package com.health.app.data.model

import com.health.app.logic.Age
import com.health.app.logic.CareLevel
import com.health.app.logic.FeverAssessment

/**
 * One person Health keeps records for, and everything true of them right now.
 */

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
