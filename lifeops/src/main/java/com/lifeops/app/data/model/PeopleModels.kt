package com.lifeops.app.data.model



/**
 * Who is in the household, how they are related, and the dates that come round.
 */

/** How much sun exposure a person tolerates — feeds the roadmap's Phase 4 outdoor scoring. */
enum class SunSensitivity(val value: String, val label: String) {
    LOW("low", "Low"),
    MODERATE("moderate", "Moderate"),
    HIGH("high", "High");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: MODERATE
    }
}

/**
 * Where a person sits in the household/relationship graph — the grouping RelationshipAnalytics
 * uses to spot imbalance (e.g. one child getting far less 1:1 time than a sibling). Unset (null
 * on Person) means "don't include this person in relationship analytics" — colleagues or
 * loosely-tracked contacts you don't want nudges about.
 */
enum class Relationship(val value: String, val label: String) {
    SPOUSE("spouse", "Spouse/Partner"),
    CHILD("child", "Child"),
    PARENT("parent", "Parent"),
    SIBLING("sibling", "Sibling"),
    EXTENDED_FAMILY("extended_family", "Extended Family"),
    FRIEND("friend", "Friend"),
    COLLEAGUE("colleague", "Colleague"),
    OTHER("other", "Other");

    companion object {
        fun from(value: String?): Relationship? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A household member. Weather-comfort preferences are all nullable ("no opinion" = never rules a
 * time out), so a person can be as simple as a name or as detailed as a full comfort profile.
 * Timeline notes are separate ([PersonNote]); task involvement is a many-to-many join.
 */
data class Person(
    val id: String,
    val name: String,
    val heatToleranceMaxF: Int? = null,
    val coldToleranceMinF: Int? = null,
    val uvMax: Int? = null,
    val windMaxMph: Int? = null,
    val maxPrecipitationPct: Int? = null,
    val sunSensitivity: SunSensitivity = SunSensitivity.MODERATE,
    val activityPreferences: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String,
    // Null = not tracked in relationship-balance analytics (see RelationshipAnalytics).
    val relationship: Relationship? = null,
    // Contact identity: how GoogleCalendarSyncRepository recognizes this person on a pulled-in
    // event's attendee list (matched case-insensitively) and where it sends a pushed event's
    // attendee row. Phone is informational for now — Android calendar events don't carry phone
    // numbers, but it's here for whichever future contact-matching source needs it.
    val email: String? = null,
    val phone: String? = null
)

data class PersonNote(
    val id: String,
    val personId: String,
    val content: String,
    val createdAt: String
)

/**
 * A rare, once-in-a-lifetime accomplishment. Recorded after the fact and granted its [points]
 * immediately (into the attached aspect's mapped resources), not at week-close. Optionally attached
 * to an [aspectId] and/or a [personId]; both are cleared (not deleted) if the aspect/person is
 * removed, mirroring the entity's ON DELETE SET NULL.
 */
data class Milestone(
    val id: String,
    val title: String,
    val description: String? = null,
    val points: Int = 0,
    val aspectId: String? = null,
    val personId: String? = null,
    val achievedAt: String,
    val createdAt: String
)
