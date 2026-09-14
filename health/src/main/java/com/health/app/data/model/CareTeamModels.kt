package com.health.app.data.model

import com.health.app.logic.CheckOutcome
import com.health.app.logic.NetworkAssessment
import com.health.app.logic.ProviderDirectory

/**
 * Providers, and how each person is attached to them.
 */

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
