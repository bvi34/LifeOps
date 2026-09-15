package com.health.app.data.model

import com.health.app.logic.CabinetStatus
import com.health.app.logic.DoseReminder
import com.health.app.logic.DoseWindow
import com.health.app.logic.DrugMonograph
import com.health.app.logic.ReminderMode
import java.time.LocalTime

/**
 * Medicines, doses, and the cabinet they are drawn from.
 */

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
