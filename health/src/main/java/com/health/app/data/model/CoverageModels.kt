package com.health.app.data.model

import com.health.app.logic.CardFacts
import com.health.app.logic.CardLayout
import com.health.app.logic.CoverageAssessment
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.Insurance
import com.health.app.logic.PlanType

/**
 * Insurance as it is printed on the card, and the card as it is drawn.
 */

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
