package com.health.app.data.model

import com.health.app.logic.Age
import com.health.app.logic.CabinetStatus
import com.health.app.logic.CardFacts
import com.health.app.logic.CardLayout
import com.health.app.logic.CareLevel
import com.health.app.logic.CheckOutcome
import com.health.app.logic.CoverageAssessment
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.DoseReminder
import com.health.app.logic.DoseWindow
import com.health.app.logic.DrugMonograph
import com.health.app.logic.FeverAssessment
import com.health.app.logic.Insurance
import com.health.app.logic.NetworkAssessment
import com.health.app.logic.PlanType
import com.health.app.logic.ProviderDirectory
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

// --- coverage and the care team -------------------------------------------------------------------

/**
 * What one person is to one provider. Not a job title — a *relationship*, which is why the same
 * paediatrician can be one child's [PRIMARY] and their cousin's [SPECIALIST] without either being
 * wrong.
 */
enum class CareRole(val key: String, val label: String) {
    PRIMARY("primary", "Primary care"),
    /** Seen by choice rather than by referral — the walk-in you'd go back to, the preferred dentist. */
    PREFERRED("preferred", "Preferred"),
    SPECIALIST("specialist", "Specialist"),
    DENTIST("dentist", "Dentist"),
    EYE("eye", "Eye care"),
    OBGYN("obgyn", "OB-GYN"),
    THERAPIST("therapist", "Therapy / mental health"),
    URGENT_CARE("urgent_care", "Urgent care"),
    PHARMACY("pharmacy", "Pharmacy"),
    OTHER("other", "Other");

    /** Primary first: it is the answer to "who do we ring?", which is what the list is read for. */
    val sortRank: Int get() = entries.indexOf(this)

    companion object {
        fun fromKey(key: String?): CareRole = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * A policy as the household holds it — one row per card, not one per person on it.
 *
 * The directory fields are the plan's, not a person's: an endpoint is a fact about the carrier, so
 * probing it once answers for everybody on the policy.
 */
data class InsurancePlan(
    val id: String,
    val carrierName: String,
    val planName: String?,
    val coverageKind: CoverageKind,
    val planType: PlanType,
    val groupNumber: String?,
    val payerId: String?,
    val rxBin: String?,
    val rxPcn: String?,
    val rxGroup: String?,
    val memberServicesPhone: String?,
    val nurseLinePhone: String?,
    val effectiveDate: String?,
    val endDate: String?,
    /** As published and as pasted. [directoryBaseUrl] is what actually answered. */
    val directoryUrl: String?,
    val directoryBaseUrl: String?,
    val directoryStatus: DirectoryOutcome,
    val directoryCheckedAt: Long?,
    val directoryDetail: String?,
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?,
    val archived: Boolean,
    val updatedAt: Long
) {
    /** "Meridian Mutual — Choice Plus", or just the carrier when nobody named the plan. */
    val displayName: String
        get() = listOfNotNull(carrierName.trim().ifBlank { null }, planName?.trim()?.ifBlank { null })
            .joinToString(" — ")

    /** The URL the next check should use: whatever answered last time, else whatever was pasted. */
    val effectiveDirectoryUrl: String?
        get() = directoryBaseUrl?.trim()?.ifBlank { null } ?: directoryUrl?.trim()?.ifBlank { null }

    /** Whether a directory check can even be attempted. Nothing recorded is not a failed check. */
    val hasDirectory: Boolean get() = !effectiveDirectoryUrl.isNullOrBlank()
}

/** One person's place on one policy — their own number on the household's card. */
data class InsuranceMembership(
    val id: String,
    val profileId: String,
    val planId: String,
    val memberId: String?,
    val personCode: String?,
    val subscriberName: String?,
    val relationshipToSubscriber: String?,
    val effectiveDate: String?,
    val endDate: String?,
    val primaryCoverage: Boolean,
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?
) {
    /** What the browsing list shows: enough to recognise the card, not enough to read out. */
    val maskedMemberId: String? get() = Insurance.maskMemberId(memberId)
}

/**
 * One person's card, assembled: the household's policy, their membership of it, whether it is
 * current, and the layout both the screen and the PDF draw from.
 *
 * [frontImagePath] falls back from the member's own photo to the policy's, which is the ordinary
 * case — one card arrives in the post for the family, gets photographed once, and everybody on it
 * has a card.
 */
data class CoverageCard(
    val plan: InsurancePlan,
    val membership: InsuranceMembership,
    val memberName: String,
    val coverage: CoverageAssessment,
    val facts: CardFacts
) {
    val layout: CardLayout get() = Insurance.card(facts)

    val frontImagePath: String?
        get() = membership.frontImagePath ?: plan.frontImagePath

    val backImagePath: String?
        get() = membership.backImagePath ?: plan.backImagePath

    val hasPhotos: Boolean get() = frontImagePath != null || backImagePath != null

    /** A file name a share sheet can show without embarrassment: `meridian-mutual-ada-card.pdf`. */
    val exportFileName: String
        get() = listOf(plan.carrierName, memberName, "card")
            .joinToString("-") { part ->
                part.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            }
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "insurance-card" } + ".pdf"
}

/** A doctor, dentist or practice, as the household wrote them down. */
data class Provider(
    val id: String,
    val name: String,
    val npi: String?,
    val specialty: String?,
    val practiceName: String?,
    val phone: String?,
    val addressLine: String?,
    val website: String?,
    val note: String?
) {
    /** "Paediatrics · Riverside Family Practice" — what they do and where, in one line. */
    val descriptor: String?
        get() = listOfNotNull(specialty?.ifBlank { null }, practiceName?.ifBlank { null })
            .joinToString(" · ")
            .ifBlank { null }

    /**
     * Whether the NPI passes its own check digit. A mistyped one is worth flagging *before* a search,
     * because a bad identifier and a doctor who has left the network both come back as no results.
     */
    val npiLooksValid: Boolean get() = npi.isNullOrBlank() || ProviderDirectory.isValidNpi(npi)
}

/** Which person sees which provider, in what capacity, and since when. */
data class ProviderLink(
    val id: String,
    val profileId: String,
    val providerId: String,
    val role: CareRole,
    val since: String?,
    val note: String?
)

/** One check, as it was recorded. The verdict comes from all of them together — see `logic/NetworkStatus`. */
data class NetworkCheck(
    val id: String,
    val providerId: String,
    val planId: String?,
    val checkedAt: Long,
    val outcome: CheckOutcome,
    val directoryLabel: String?,
    val directoryUrl: String?,
    val matchedName: String?,
    val matchedNpi: String?,
    val matchCount: Int,
    val networks: List<String>,
    val detail: String?
)

/**
 * A provider as one person's care team holds them: who they are, what they are to this person, and
 * where they stand against that person's coverage.
 *
 * [network] is derived from the whole check history rather than stored, so "listed in March's
 * directory, not in today's" survives being read back — see `logic/NetworkStatus`.
 */
data class CareTeamMember(
    val provider: Provider,
    val link: ProviderLink,
    val network: NetworkAssessment,
    /** The checks behind the verdict, oldest first. What the "how do you know?" sheet shows. */
    val checks: List<NetworkCheck>
)
