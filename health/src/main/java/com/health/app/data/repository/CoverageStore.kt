package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.InsuranceMemberEntity
import com.health.app.data.db.entities.InsurancePlanEntity
import com.health.app.data.model.CoverageCard
import com.health.app.data.model.InsuranceMembership
import com.health.app.data.model.InsurancePlan
import com.health.app.logic.CardFacts
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryProbe
import com.health.app.logic.Insurance
import com.health.app.logic.PlanType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Insurance as it is printed on the card: the policies, and who in the house is on which.
 *
 * Stores what the card says and nothing about what the policy actually covers — the moment a health
 * app starts inferring coverage it starts being wrong at a reception desk.
 */
class CoverageStore(
    private val dao: HealthDao,
    private val onCardImageDiscarded: (fileName: String) -> Unit
) {

    fun observeInsurancePlans(): Flow<List<InsurancePlan>> =
        dao.observeInsurancePlans().map { rows -> rows.map { it.toModel() } }

    suspend fun getInsurancePlan(id: String): InsurancePlan? = dao.getInsurancePlan(id)?.toModel()

    /**
     * One person's cards: each policy they are on, their own membership of it, whether it is current,
     * and the layout the screen and the PDF both draw from.
     *
     * Assembled here rather than at the screen for the reason every other combined flow in this file
     * exists: two renderers deriving a card independently is two renderers that will eventually
     * disagree about a member number, and the place that shows up is a reception desk.
     *
     * Sorted by the coverage verdict — an ended card first, then one not yet started, then one about
     * to end — with the primary card ahead of a secondary within each. A wallet is read top-down when
     * something is wrong with it.
     */
    fun observeCoverage(profileId: String): Flow<List<CoverageCard>> =
        combine(
            dao.observeInsurancePlans(),
            dao.observeInsuranceMembers(profileId),
            dao.observeProfiles()
        ) { plans, members, profiles ->
            val plansById = plans.associateBy { it.id }
            val memberName = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
            members.mapNotNull { member ->
                plansById[member.planId]?.let { plan -> card(plan, member, memberName) }
            }.sortedWith(
                compareBy({ it.coverage.status.sortRank }, { !it.membership.primaryCoverage })
            )
        }

    /** One card, for the export sheet — the same assembly [observeCoverage] does, for a single row. */
    suspend fun getCoverageCard(membershipId: String): CoverageCard? {
        val member = dao.getInsuranceMember(membershipId) ?: return null
        val plan = dao.getInsurancePlan(member.planId) ?: return null
        val name = dao.getProfile(member.profileId)?.name.orEmpty()
        return card(plan, member, name)
    }

    private fun card(
        plan: InsurancePlanEntity,
        member: InsuranceMemberEntity,
        memberName: String
    ): CoverageCard {
        // The member's own dates win where they were recorded, and fall back to the policy's. They
        // differ for the ordinary reason: a baby added to a family plan in March is covered from
        // March, not from the policy's January.
        val effective = member.effectiveDate?.ifBlank { null } ?: plan.effectiveDate
        val ends = member.endDate?.ifBlank { null } ?: plan.endDate
        return CoverageCard(
            plan = plan.toModel(),
            membership = member.toModel(),
            memberName = memberName,
            coverage = Insurance.assessCoverage(effective, ends),
            facts = CardFacts(
                carrierName = plan.carrierName,
                planName = plan.planName,
                coverageKind = CoverageKind.fromKey(plan.coverageKind),
                planType = PlanType.fromKey(plan.planType),
                memberName = memberName,
                memberId = member.memberId,
                personCode = member.personCode,
                groupNumber = plan.groupNumber,
                subscriberName = member.subscriberName,
                relationshipToSubscriber = member.relationshipToSubscriber,
                payerId = plan.payerId,
                rxBin = plan.rxBin,
                rxPcn = plan.rxPcn,
                rxGroup = plan.rxGroup,
                effectiveDate = effective,
                endDate = ends,
                memberServicesPhone = plan.memberServicesPhone,
                nurseLinePhone = plan.nurseLinePhone,
                directoryUrl = plan.directoryUrl,
                note = plan.note
            )
        )
    }

    /**
     * Add a policy, and optionally put somebody on it in the same gesture — which is what actually
     * happens when a card comes out of an envelope.
     *
     * Only the carrier is required. A card photographed in a hurry with nothing typed in but the
     * insurer's name is still a card in the app rather than a card in a drawer.
     */
    suspend fun addInsurancePlan(
        carrierName: String,
        planName: String? = null,
        coverageKind: CoverageKind = CoverageKind.MEDICAL,
        planType: PlanType = PlanType.OTHER,
        groupNumber: String? = null,
        payerId: String? = null,
        rxBin: String? = null,
        rxPcn: String? = null,
        rxGroup: String? = null,
        memberServicesPhone: String? = null,
        nurseLinePhone: String? = null,
        effectiveDate: String? = null,
        endDate: String? = null,
        directoryUrl: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertInsurancePlan(
            InsurancePlanEntity(
                id = id,
                carrierName = carrierName.trim(),
                planName = planName.clean(),
                coverageKind = coverageKind.key,
                planType = planType.key,
                groupNumber = groupNumber.clean(),
                payerId = payerId.clean(),
                rxBin = rxBin.clean(),
                rxPcn = rxPcn.clean(),
                rxGroup = rxGroup.clean(),
                memberServicesPhone = memberServicesPhone.clean(),
                nurseLinePhone = nurseLinePhone.clean(),
                effectiveDate = effectiveDate.clean(),
                endDate = endDate.clean(),
                directoryUrl = directoryUrl.clean(),
                directoryBaseUrl = null,
                directoryStatus = null,
                directoryCheckedAt = null,
                directoryDetail = null,
                frontImagePath = null,
                backImagePath = null,
                note = note.clean(),
                archived = false,
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    /**
     * Edit a policy. The directory columns are left alone: they are written by a probe, not by a
     * form, and an edit to the phone number is no reason to forget that the directory answered last
     * Tuesday. Changing the published URL *does* clear them — see below — because a new address makes
     * the old verdict meaningless.
     */
    suspend fun updateInsurancePlan(plan: InsurancePlan) {
        val existing = dao.getInsurancePlan(plan.id) ?: return
        val newUrl = plan.directoryUrl.clean()
        val urlChanged = newUrl != existing.directoryUrl
        dao.upsertInsurancePlan(
            existing.copy(
                carrierName = plan.carrierName.trim(),
                planName = plan.planName.clean(),
                coverageKind = plan.coverageKind.key,
                planType = plan.planType.key,
                groupNumber = plan.groupNumber.clean(),
                payerId = plan.payerId.clean(),
                rxBin = plan.rxBin.clean(),
                rxPcn = plan.rxPcn.clean(),
                rxGroup = plan.rxGroup.clean(),
                memberServicesPhone = plan.memberServicesPhone.clean(),
                nurseLinePhone = plan.nurseLinePhone.clean(),
                effectiveDate = plan.effectiveDate.clean(),
                endDate = plan.endDate.clean(),
                directoryUrl = newUrl,
                directoryBaseUrl = if (urlChanged) null else existing.directoryBaseUrl,
                directoryStatus = if (urlChanged) null else existing.directoryStatus,
                directoryCheckedAt = if (urlChanged) null else existing.directoryCheckedAt,
                directoryDetail = if (urlChanged) null else existing.directoryDetail,
                note = plan.note.clean(),
                archived = plan.archived,
                updatedAt = now()
            )
        )
    }

    /**
     * Put a policy away without losing it. Last year's plan is still the plan that covered a visit in
     * November, and the checks recorded under it are still the evidence that a doctor used to be in
     * network — which is the one thing that makes this year's "not listed" mean anything.
     */
    suspend fun setInsurancePlanArchived(planId: String, archived: Boolean) {
        val existing = dao.getInsurancePlan(planId) ?: return
        dao.upsertInsurancePlan(existing.copy(archived = archived, updatedAt = now()))
    }

    /** Record what a directory probe found, so the next check starts from the endpoint that answered. */
    suspend fun recordDirectoryProbe(planId: String, probe: DirectoryProbe) {
        val existing = dao.getInsurancePlan(planId) ?: return
        dao.upsertInsurancePlan(
            existing.copy(
                // Only a probe that actually found a directory rewrites the base URL. A failed one
                // records what happened without throwing away the endpoint that worked last month.
                directoryBaseUrl = if (probe.searchable) probe.baseUrl else existing.directoryBaseUrl,
                directoryStatus = probe.outcome.key,
                directoryCheckedAt = now(),
                directoryDetail = probe.detail.clean(),
                updatedAt = now()
            )
        )
    }

    /** Throw a policy away, along with everybody's membership of it and any card photos it held. */
    suspend fun deleteInsurancePlan(planId: String) {
        val existing = dao.getInsurancePlan(planId) ?: return
        val orphaned = dao.getInsuranceMembersForPlan(planId)
            .flatMap { listOfNotNull(it.frontImagePath, it.backImagePath) } +
            listOfNotNull(existing.frontImagePath, existing.backImagePath)
        dao.deleteInsurancePlanCascade(planId)
        orphaned.forEach(onCardImageDiscarded)
    }

    // --- coverage: who is on which card ------------------------------------------------------------

    fun observeMemberships(profileId: String): Flow<List<InsuranceMembership>> =
        dao.observeInsuranceMembers(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addMembership(
        profileId: String,
        planId: String,
        memberId: String? = null,
        personCode: String? = null,
        subscriberName: String? = null,
        relationshipToSubscriber: String? = null,
        effectiveDate: String? = null,
        endDate: String? = null,
        primaryCoverage: Boolean = true,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertInsuranceMember(
            InsuranceMemberEntity(
                id = id,
                profileId = profileId,
                planId = planId,
                memberId = memberId.clean(),
                personCode = personCode.clean(),
                subscriberName = subscriberName.clean(),
                relationshipToSubscriber = relationshipToSubscriber.clean(),
                effectiveDate = effectiveDate.clean(),
                endDate = endDate.clean(),
                primaryCoverage = primaryCoverage,
                frontImagePath = null,
                backImagePath = null,
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateMembership(membership: InsuranceMembership) {
        val existing = dao.getInsuranceMember(membership.id) ?: return
        dao.upsertInsuranceMember(
            existing.copy(
                planId = membership.planId,
                memberId = membership.memberId.clean(),
                personCode = membership.personCode.clean(),
                subscriberName = membership.subscriberName.clean(),
                relationshipToSubscriber = membership.relationshipToSubscriber.clean(),
                effectiveDate = membership.effectiveDate.clean(),
                endDate = membership.endDate.clean(),
                primaryCoverage = membership.primaryCoverage,
                note = membership.note.clean(),
                updatedAt = now()
            )
        )
    }

    /** Take somebody off a card, keeping the card and the photos that belong to the policy itself. */
    suspend fun deleteMembership(membershipId: String) {
        val existing = dao.getInsuranceMember(membershipId) ?: return
        val orphaned = listOfNotNull(existing.frontImagePath, existing.backImagePath)
        dao.deleteInsuranceMember(membershipId)
        orphaned.forEach(onCardImageDiscarded)
    }

    /**
     * Attach — or replace — the photographs of a card.
     *
     * Saved on upload rather than re-read from the picker each time, which is the whole reason the
     * PDF can be produced later without asking for the image again. The previous file for that face
     * is discarded once the row pointing at it is written, never before: a file deleted ahead of a
     * write that then fails leaves a card pointing at nothing.
     *
     * Passing null for a face leaves it alone; [clearFront]/[clearBack] is how a photo is removed,
     * because "no new file" and "delete the one there" are different intentions and conflating them
     * makes the second one unreachable.
     */
    suspend fun setMembershipCardImages(
        membershipId: String,
        frontFileName: String? = null,
        backFileName: String? = null,
        clearFront: Boolean = false,
        clearBack: Boolean = false
    ) {
        val existing = dao.getInsuranceMember(membershipId) ?: return
        val nextFront = when {
            clearFront -> null
            frontFileName != null -> frontFileName
            else -> existing.frontImagePath
        }
        val nextBack = when {
            clearBack -> null
            backFileName != null -> backFileName
            else -> existing.backImagePath
        }
        dao.upsertInsuranceMember(
            existing.copy(frontImagePath = nextFront, backImagePath = nextBack, updatedAt = now())
        )
        listOfNotNull(
            existing.frontImagePath.takeIf { it != nextFront },
            existing.backImagePath.takeIf { it != nextBack }
        ).forEach(onCardImageDiscarded)
    }

    /** The same, for the household card that came in one envelope for everybody on the policy. */
    suspend fun setPlanCardImages(
        planId: String,
        frontFileName: String? = null,
        backFileName: String? = null,
        clearFront: Boolean = false,
        clearBack: Boolean = false
    ) {
        val existing = dao.getInsurancePlan(planId) ?: return
        val nextFront = when {
            clearFront -> null
            frontFileName != null -> frontFileName
            else -> existing.frontImagePath
        }
        val nextBack = when {
            clearBack -> null
            backFileName != null -> backFileName
            else -> existing.backImagePath
        }
        dao.upsertInsurancePlan(
            existing.copy(frontImagePath = nextFront, backImagePath = nextBack, updatedAt = now())
        )
        listOfNotNull(
            existing.frontImagePath.takeIf { it != nextFront },
            existing.backImagePath.takeIf { it != nextBack }
        ).forEach(onCardImageDiscarded)
    }
}
