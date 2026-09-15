package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Insurance as it is printed on the card: the policy, and who in the house is on it.
 */

// --- coverage and care team -----------------------------------------------------------------------
//
// Five tables that between them answer the two questions the medical half of this app never could:
// **who pays for this**, and **who do we take her to**. They follow the same split the cabinet
// established — the thing itself is household-scoped, the person's use of it is not — because a
// family policy and a family doctor are both single objects that several people share.

/**
 * One insurance policy, as copied off the card.
 *
 * Household-scoped like [CabinetItemEntity], and for the same reason: a family plan is *one* policy
 * with one carrier, one group number and one set of phone numbers on the back, and duplicating it
 * per person would be four rows to keep in step and three of them out of date by renewal. What
 * varies per person — the member number, the person code, who the subscriber is — lives on
 * [InsuranceMemberEntity], which points here.
 *
 * **Health stores a card, not a policy.** There is no column for a deductible, a copay, a
 * coinsurance share or an out-of-pocket maximum, and that is deliberate. Those are the terms of a
 * legal document that runs to eighty pages and changes by service; an app that let you type "$30
 * copay" into a box would be inviting somebody to plan around a number nobody checked. What is here
 * is what is printed on the card and useful at a desk.
 *
 * [directoryUrl] is what the plan published; [directoryBaseUrl] is the endpoint that actually
 * answered when Health probed it, which is usually not the same string — see
 * `logic/ProviderDirectory`. Both are kept: the first is what the user pasted and can correct, the
 * second is what the next check should use.
 *
 * The card images are file *names*, not blobs. See [InsuranceMemberEntity] for why.
 */
@Entity(
    tableName = "insurance_plans",
    indices = [Index("carrierName"), Index("archived")]
)
data class InsurancePlanEntity(
    @PrimaryKey val id: String,
    val carrierName: String,
    val planName: String?,
    /** [com.health.app.logic.CoverageKind]'s key — medical, dental, vision, pharmacy. */
    val coverageKind: String,
    /** [com.health.app.logic.PlanType]'s key — HMO, PPO, and the rest of what cards print. */
    val planType: String,
    val groupNumber: String?,
    val payerId: String?,
    val rxBin: String?,
    val rxPcn: String?,
    val rxGroup: String?,
    val memberServicesPhone: String?,
    val nurseLinePhone: String?,
    /** ISO `yyyy-MM-dd`. A card with neither date is coverage Health declines to judge. */
    val effectiveDate: String?,
    val endDate: String?,
    /** The provider directory address as published — what the user pasted, kept as they pasted it. */
    val directoryUrl: String?,
    /** The FHIR base that actually answered. Written by the probe, not by the form. */
    val directoryBaseUrl: String?,
    /** [com.health.app.logic.DirectoryOutcome]'s key, from the last probe. */
    val directoryStatus: String?,
    val directoryCheckedAt: Long?,
    val directoryDetail: String?,
    /** File names under `insurance-cards/`, not blobs. See [InsuranceMemberEntity]. */
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?,
    /**
     * Last year's plan, kept rather than deleted. A policy that has ended is still the policy that
     * covered a visit in November, and the network checks recorded against it are still evidence.
     */
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One person's membership of one plan — their own number on the household's policy.
 *
 * This is the row that makes a family plan work: four people, one [InsurancePlanEntity], four member
 * ids and four person codes. It is also the row that lets a household hold two plans at once
 * (primary and secondary, or a parent's medical and an employer's dental) without either becoming a
 * duplicate of the other.
 *
 * ### Why the card photos are file names
 *
 * [frontImagePath] and [backImagePath] hold a **file name** under Health's `insurance-cards/`
 * directory, never image bytes. A card photo is a couple of megabytes; a database that carries four
 * of them is a database that is copied, WAL-checkpointed and backed up in full every time anybody
 * records a temperature. The files sit beside the database, are carried by the same backup, and are
 * deleted with the row that names them — see `data/store/CardImageStore`.
 *
 * The plan's own images are the fallback: photograph the one card that came in the post, attach it
 * to the policy, and everybody on it has a card. A member's own images override that for the
 * households where each person's card really is different.
 */
@Entity(
    tableName = "insurance_members",
    indices = [Index("profileId"), Index("planId")]
)
data class InsuranceMemberEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val planId: String,
    val memberId: String?,
    /** The two-digit suffix that tells a family plan's members apart. Printed as "Person code"/"Dep #". */
    val personCode: String?,
    val subscriberName: String?,
    val relationshipToSubscriber: String?,
    /** When *this person's* cover started, when it differs from the policy's own dates. */
    val effectiveDate: String?,
    val endDate: String?,
    /** Which card gets handed over first when somebody carries two. */
    val primaryCoverage: Boolean = true,
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)
