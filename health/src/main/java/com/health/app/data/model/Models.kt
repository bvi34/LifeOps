package com.health.app.data.model

import com.health.app.logic.Age
import com.health.app.logic.CabinetStatus
import com.health.app.logic.CareLevel
import com.health.app.logic.DoseReminder
import com.health.app.logic.DoseWindow
import com.health.app.logic.DrugMonograph
import com.health.app.logic.FeverAssessment
import com.health.app.logic.ReminderMode
import com.health.app.logic.TempSite
import java.time.LocalTime

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

/**
 * One person's use of a medicine: the dose *they* take and the limits from the label it is given
 * under. The product's own facts (what it is made of, what the label says) live once per household
 * in [DrugMonograph]; the bottle it comes out of lives once in [CabinetItem]. This row links to
 * both and duplicates neither.
 */
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
    val active: Boolean,
    val rxcui: String? = null,
    val cabinetItemId: String? = null,
    val reminderMode: ReminderMode = ReminderMode.OFF,
    val reminderTimes: List<LocalTime> = emptyList()
) {
    /**
     * Whether a reminder is actually armed. A paused medicine never nudges — pausing is how somebody
     * says "not at the moment", and a notification that ignores that is the fastest way to teach
     * people to swipe reminders away without reading them. Set times with no times set is likewise
     * off rather than an error.
     */
    val reminderArmed: Boolean
        get() = active && when (reminderMode) {
            ReminderMode.OFF -> false
            ReminderMode.FIXED_TIMES -> reminderTimes.isNotEmpty()
            ReminderMode.WHEN_DUE -> true
        }

    /** How the reminder reads on a card. */
    val reminderSummary: String
        get() = when {
            !active && reminderMode != ReminderMode.OFF -> "Reminder paused with the medicine"
            reminderMode == ReminderMode.OFF -> ReminderMode.OFF.label
            reminderMode == ReminderMode.FIXED_TIMES -> DoseReminder.describeTimes(reminderTimes)
            else -> ReminderMode.WHEN_DUE.label
        }
}

/**
 * One thing in the medicine cabinet — a bottle, a box, a blister pack.
 *
 * Household-scoped, not per person: the stock and the expiry date are facts about the object, and a
 * copy per person would be four dates to keep in step and three of them wrong. Who takes it, and how
 * much, is [Medication].
 */
data class CabinetItem(
    val id: String,
    val rxcui: String?,
    val name: String,
    val brandName: String?,
    val strength: String?,
    val form: String?,
    val quantity: Double?,
    val quantityUnit: String,
    val expiryDate: String?,
    val location: String?,
    val lowStockThreshold: Double?,
    val note: String?,
    val updatedAt: Long
) {
    val displayName: String
        get() = listOfNotNull(name, strength?.takeIf { it.isNotBlank() && !name.contains(it, true) })
            .joinToString(" ")

    /** "Oral suspension · Kitchen cupboard" — what it is and where, the two things you're after. */
    val descriptor: String?
        get() = listOfNotNull(form?.ifBlank { null }, location?.ifBlank { null })
            .joinToString(" · ")
            .ifBlank { null }
}

/**
 * A cabinet item with the two verdicts about it and everyone it is given to.
 *
 * [takenBy] is what makes the cabinet more than a shopping list: standing in front of a bottle, the
 * question is rarely "what is this" and almost always "how much of this does *she* have, and when
 * did she last get some" — so the item carries each person's own dose rules and live dose window
 * with it. See `logic/Cabinet` and `logic/DoseSchedule`; neither invents anything the label didn't
 * say.
 */
data class CabinetEntry(
    val item: CabinetItem,
    val status: CabinetStatus,
    val monograph: DrugMonograph?,
    val takenBy: List<CabinetUse>
)

/** One person's dose of one cabinet item, with the answer to "can they have some yet?". */
data class CabinetUse(
    val profile: Profile,
    val medication: Medication,
    val window: DoseWindow
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

/**
 * A medicine paired with the answer to "can I give it yet?" — see `logic/DoseSchedule` — and, when
 * the household is tracking the bottle, whether there is any of it left.
 *
 * [cabinetItem] being null is the ordinary case for a medicine typed in by hand, and means only that
 * nobody is counting the stock; it never means the medicine is unavailable.
 */
data class MedicationStatus(
    val medication: Medication,
    val window: DoseWindow,
    val cabinetItem: CabinetItem? = null,
    val cabinetStatus: CabinetStatus? = null,
    val monograph: DrugMonograph? = null
)

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
