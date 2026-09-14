package com.health.app.ui.coverage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.card.InsuranceCardPdf
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.CoverageCard
import com.health.app.data.model.InsuranceMembership
import com.health.app.data.model.InsurancePlan
import com.health.app.data.model.Profile
import com.health.app.data.model.Provider
import com.health.app.data.model.ProviderLink
import com.health.app.data.net.ProviderDirectoryClient
import com.health.app.data.repository.HealthRepository
import com.health.app.data.store.CardImageStore
import com.health.app.logic.CheckOutcome
import com.health.app.logic.CoverageKind
import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.DirectoryProbe
import com.health.app.logic.PlanType
import com.health.app.logic.ProviderDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the directory is doing right now, and what it last said.
 *
 * [checkingProviderIds] is a set rather than a flag because "check everybody" runs a whole care team
 * at once and each row shows its own spinner — a single boolean would either freeze the screen or
 * leave five rows looking idle while they are being checked.
 */
data class DirectoryState(
    val probingPlanId: String? = null,
    val checkingProviderIds: Set<String> = emptySet(),
    /** The last thing worth saying to the user — an error, or the outcome of a probe. */
    val message: String? = null
) {
    val busy: Boolean get() = probingPlanId != null || checkingProviderIds.isNotEmpty()
}

/**
 * The Care tab's state: the household's policies, the selected person's cards, their care team, and
 * the directory checks that connect the two.
 *
 * The directory is the only part of this screen that can fail for reasons outside the device, so it
 * is the only part with a message in its state — the same shape the Meds tab's drug lookup has, for
 * the same reason. Everything else is a database flow that either has rows or doesn't.
 *
 * **Nothing here sends anything about a person.** The check functions below hand
 * [ProviderDirectoryClient] a provider's name and NPI and nothing else; the plan is used for its
 * directory address and its carrier's name, never for a member number. See the module manifest for
 * the whole argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverageViewModel(
    private val repo: HealthRepository,
    private val directory: ProviderDirectoryClient,
    val images: CardImageStore
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        repo.profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected: StateFlow<Profile?> =
        repo.profiles.observeSelectedProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every policy the household holds — not scoped to the selected person, because a policy isn't. */
    val plans: StateFlow<List<InsurancePlan>> =
        repo.coverage.observeInsurancePlans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every provider the household sees, for putting an existing doctor on somebody else's team. */
    val providers: StateFlow<List<Provider>> =
        repo.careTeam.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val coverage: StateFlow<List<CoverageCard>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.coverage.observeCoverage(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val careTeam: StateFlow<List<CareTeamMember>> = selected
        .flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else repo.careTeam.observeCareTeam(profile.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _directoryState = MutableStateFlow(DirectoryState())
    val directoryState: StateFlow<DirectoryState> = _directoryState.asStateFlow()

    fun select(profile: Profile) = repo.profiles.selectProfile(profile.id)

    fun dismissMessage() {
        _directoryState.value = _directoryState.value.copy(message = null)
    }

    private fun say(message: String?) {
        _directoryState.value = _directoryState.value.copy(message = message)
    }

    // --- the cards ----------------------------------------------------------------------------------

    fun addPlan(draft: PlanDraft, alsoForProfile: MembershipDraft? = null) = viewModelScope.launch {
        val planId = repo.coverage.addInsurancePlan(
            carrierName = draft.carrierName,
            planName = draft.planName,
            coverageKind = draft.coverageKind,
            planType = draft.planType,
            groupNumber = draft.groupNumber,
            payerId = draft.payerId,
            rxBin = draft.rxBin,
            rxPcn = draft.rxPcn,
            rxGroup = draft.rxGroup,
            memberServicesPhone = draft.memberServicesPhone,
            nurseLinePhone = draft.nurseLinePhone,
            effectiveDate = draft.effectiveDate,
            endDate = draft.endDate,
            directoryUrl = draft.directoryUrl,
            note = draft.note
        )
        alsoForProfile?.let { membership -> saveMembership(planId, membership) }
    }

    fun updatePlan(plan: InsurancePlan) = viewModelScope.launch { repo.coverage.updateInsurancePlan(plan) }

    fun setPlanArchived(planId: String, archived: Boolean) =
        viewModelScope.launch { repo.coverage.setInsurancePlanArchived(planId, archived) }

    fun deletePlan(planId: String) = viewModelScope.launch { repo.coverage.deleteInsurancePlan(planId) }

    /** Put the selected person on a policy — the second half of "a card came in the post". */
    fun addMembership(planId: String, draft: MembershipDraft) = viewModelScope.launch {
        saveMembership(planId, draft)
    }

    private suspend fun saveMembership(planId: String, draft: MembershipDraft) {
        val profile = selected.value ?: return
        repo.coverage.addMembership(
            profileId = profile.id,
            planId = planId,
            memberId = draft.memberId,
            personCode = draft.personCode,
            subscriberName = draft.subscriberName,
            relationshipToSubscriber = draft.relationshipToSubscriber,
            effectiveDate = draft.effectiveDate,
            endDate = draft.endDate,
            primaryCoverage = draft.primaryCoverage,
            note = draft.note
        )
    }

    fun updateMembership(membership: InsuranceMembership) =
        viewModelScope.launch { repo.coverage.updateMembership(membership) }

    fun deleteMembership(membershipId: String) =
        viewModelScope.launch { repo.coverage.deleteMembership(membershipId) }

    /**
     * Save a photographed card — which is what makes the PDF possible later without asking for the
     * picture again.
     *
     * [toPlan] decides which row it lands on, and the difference matters: a photo attached to the
     * *policy* is the one envelope everybody on it shares, while one attached to a *membership* is
     * this person's own card. The card view falls back from the second to the first.
     */
    fun attachCardImage(
        planId: String,
        membershipId: String?,
        source: Uri,
        front: Boolean,
        toPlan: Boolean
    ) = viewModelScope.launch {
        val fileName = images.save(source)
        if (fileName == null) {
            say("That picture couldn't be read. Try photographing the card again.")
            return@launch
        }
        if (toPlan || membershipId == null) {
            repo.coverage.setPlanCardImages(
                planId = planId,
                frontFileName = fileName.takeIf { front },
                backFileName = fileName.takeIf { !front }
            )
        } else {
            repo.coverage.setMembershipCardImages(
                membershipId = membershipId,
                frontFileName = fileName.takeIf { front },
                backFileName = fileName.takeIf { !front }
            )
        }
    }

    fun clearCardImage(planId: String, membershipId: String?, front: Boolean, fromPlan: Boolean) =
        viewModelScope.launch {
            if (fromPlan || membershipId == null) {
                repo.coverage.setPlanCardImages(planId = planId, clearFront = front, clearBack = !front)
            } else {
                repo.coverage.setMembershipCardImages(
                    membershipId = membershipId,
                    clearFront = front,
                    clearBack = !front
                )
            }
        }

    /**
     * Draw the card as a PDF and hand it to the share sheet.
     *
     * Drawn off the main thread — it decodes two photographs — and shared back on it, because
     * starting an activity is a main-thread job. A failure at either end is reported rather than
     * swallowed: a tap that appears to do nothing is the worst possible outcome for a feature whose
     * whole promise is "produce the card, now".
     */
    fun exportCard(context: Context, card: CoverageCard) = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { InsuranceCardPdf.write(context, card, images) }
        if (file == null) {
            say("The card couldn't be written.")
            return@launch
        }
        val shared = InsuranceCardPdf.shareFile(
            context = context,
            file = file,
            subject = "${card.plan.carrierName} — ${card.memberName}"
        )
        if (!shared) say("Nothing on this device offered to open a PDF.")
    }

    // --- the care team ------------------------------------------------------------------------------

    fun addProvider(draft: ProviderDraft, role: CareRole) = viewModelScope.launch {
        val profile = selected.value ?: return@launch
        repo.careTeam.addProvider(
            name = draft.name,
            npi = draft.npi,
            specialty = draft.specialty,
            practiceName = draft.practiceName,
            phone = draft.phone,
            addressLine = draft.addressLine,
            website = draft.website,
            note = draft.note,
            forProfileId = profile.id,
            role = role
        )
    }

    fun updateProvider(provider: Provider) = viewModelScope.launch { repo.careTeam.updateProvider(provider) }

    fun deleteProvider(providerId: String) = viewModelScope.launch { repo.careTeam.deleteProvider(providerId) }

    /** Put a doctor the household already sees on this person's team too. */
    fun linkExistingProvider(providerId: String, role: CareRole) = viewModelScope.launch {
        val profile = selected.value ?: return@launch
        repo.careTeam.linkProvider(profile.id, providerId, role)
    }

    fun setRole(link: ProviderLink, role: CareRole) =
        viewModelScope.launch { repo.careTeam.updateProviderLink(link.copy(role = role)) }

    fun unlink(linkId: String) = viewModelScope.launch { repo.careTeam.unlinkProvider(linkId) }

    // --- the directory ------------------------------------------------------------------------------

    /**
     * Ask a plan's directory address whether there is a directory there.
     *
     * Worth doing on its own — before any doctor is checked — because the answer is usually the
     * useful one. "That's the member portal, not the directory" is something the user can fix, and
     * discovering it while checking a paediatrician makes it look like a problem with the
     * paediatrician.
     */
    fun probeDirectory(plan: InsurancePlan) = viewModelScope.launch {
        if (!plan.hasDirectory) {
            say(
                "Add the plan's provider-directory address first — it's usually on the back of the " +
                    "card, or on the insurer's developer page."
            )
            return@launch
        }
        _directoryState.value = _directoryState.value.copy(probingPlanId = plan.id, message = null)
        val probe = probeOnce(plan)
        repo.coverage.recordDirectoryProbe(plan.id, probe)
        _directoryState.value = _directoryState.value.copy(probingPlanId = null, message = describe(probe))
    }

    /**
     * Check one doctor against one plan, and write down what came back.
     *
     * The whole sequence, and every branch of it ends in something recorded or something said —
     * never in a silence:
     *
     *  1. **No directory address** — nothing is recorded, because nothing was asked, and the user is
     *     told what to add. An "unavailable" row here would be Health blaming a network for a blank
     *     field.
     *  2. **The directory can't be found or searched** — recorded as [CheckOutcome.UNAVAILABLE],
     *     carrying the probe's own explanation. That is not a verdict about the doctor, and
     *     `logic/NetworkStatus` deliberately does not let it overwrite one.
     *  3. **The directory answered** — [ProviderDirectory.judge] turns the results into listed, not
     *     listed, or "couldn't tell them apart", and the row keeps the matched entry's name and the
     *     networks it was found in.
     */
    fun checkNetwork(member: CareTeamMember, plan: InsurancePlan) = viewModelScope.launch {
        checkOne(member, plan)
    }

    /** The same, for a whole care team — which is what somebody does the week a new plan starts. */
    fun checkAll(members: List<CareTeamMember>, plan: InsurancePlan) = viewModelScope.launch {
        members.forEach { checkOne(it, plan) }
    }

    private suspend fun checkOne(member: CareTeamMember, plan: InsurancePlan) {
        val provider = member.provider
        if (!plan.hasDirectory) {
            say(
                "${plan.carrierName} has no directory address recorded, so there is nothing to ask. " +
                    "Add it to the plan, or record what the office told you by phone."
            )
            return
        }

        _directoryState.value = _directoryState.value.copy(
            checkingProviderIds = _directoryState.value.checkingProviderIds + provider.id,
            message = null
        )
        try {
            val probe = probeOnce(plan)
            repo.coverage.recordDirectoryProbe(plan.id, probe)

            val base = probe.baseUrl
            if (!probe.searchable || base == null) {
                repo.networkChecks.recordNetworkCheck(
                    providerId = provider.id,
                    planId = plan.id,
                    outcome = CheckOutcome.UNAVAILABLE,
                    directoryLabel = plan.carrierName,
                    directoryUrl = plan.effectiveDirectoryUrl,
                    detail = probe.detail ?: "The plan's directory couldn't be searched."
                )
                say(describe(probe))
                return
            }

            val result = runCatching { directory.findPractitioner(base, provider.name, provider.npi) }
                .rethrowCancellation()
                .getOrElse { failure ->
                    repo.networkChecks.recordNetworkCheck(
                        providerId = provider.id,
                        planId = plan.id,
                        outcome = CheckOutcome.UNAVAILABLE,
                        directoryLabel = plan.carrierName,
                        directoryUrl = base,
                        detail = failure.readableMessage()
                    )
                    say(failure.readableMessage())
                    return
                }

            // A server that complained instead of searching has told us nothing about the doctor.
            result.message?.let { complaint ->
                repo.networkChecks.recordNetworkCheck(
                    providerId = provider.id,
                    planId = plan.id,
                    outcome = CheckOutcome.UNAVAILABLE,
                    directoryLabel = plan.carrierName,
                    directoryUrl = result.queriedUrl,
                    detail = complaint
                )
                say(complaint)
                return
            }

            val verdict = ProviderDirectory.judge(
                providerName = provider.name,
                providerNpi = provider.npi,
                results = result.practitioners,
                byIdentifier = result.byIdentifier
            )
            repo.networkChecks.recordNetworkCheck(
                providerId = provider.id,
                planId = plan.id,
                outcome = verdict.outcome,
                directoryLabel = plan.carrierName,
                directoryUrl = result.queriedUrl,
                matchedName = verdict.matched?.name,
                matchedNpi = verdict.matched?.npi,
                matchCount = verdict.matchCount,
                networks = verdict.matched?.networks.orEmpty(),
                detail = verdict.detail
            )
        } finally {
            _directoryState.value = _directoryState.value.copy(
                checkingProviderIds = _directoryState.value.checkingProviderIds - provider.id
            )
        }
    }

    private suspend fun probeOnce(plan: InsurancePlan): DirectoryProbe =
        runCatching { directory.probe(plan.effectiveDirectoryUrl) }
            .rethrowCancellation()
            .getOrElse { failure ->
                DirectoryProbe(outcome = DirectoryOutcome.UNREACHABLE, detail = failure.readableMessage())
            }

    /**
     * Record what a human was told on the phone.
     *
     * Kept in the same history as the directory's own answers, and kept *as* a phone call: the
     * verdict reads "confirmed by phone", never "in network". Somebody was told something once, by
     * somebody who may well have been reading the same directory — that is worth recording, dating
     * and showing, and it is not a published listing.
     */
    fun recordByPhone(
        providerId: String,
        plan: InsurancePlan?,
        inNetwork: Boolean,
        note: String?
    ) = viewModelScope.launch {
        repo.networkChecks.recordNetworkCheck(
            providerId = providerId,
            planId = plan?.id,
            outcome = if (inNetwork) CheckOutcome.CONFIRMED_BY_HAND else CheckOutcome.DECLINED_BY_HAND,
            directoryLabel = plan?.carrierName,
            detail = note?.trim()?.ifBlank { null }
        )
    }

    fun deleteCheck(checkId: String) = viewModelScope.launch { repo.networkChecks.deleteNetworkCheck(checkId) }

    private fun describe(probe: DirectoryProbe): String = when {
        probe.searchable ->
            "Found ${probe.softwareName ?: "a provider directory"} at ${probe.baseUrl}" +
                (probe.fhirVersion?.let { " (FHIR $it)" } ?: "") + "."
        probe.outcome == DirectoryOutcome.REACHABLE ->
            "That address is a FHIR server, but it doesn't publish practitioners — so it isn't a " +
                "provider directory."
        else -> probe.detail ?: probe.outcome.label
    }

    /**
     * Let a cancellation stay a cancellation — the same guard the Meds tab's lookup needs.
     *
     * `runCatching` catches [CancellationException] along with everything else, so without this a
     * check abandoned because the user left the screen would be recorded as though the directory had
     * failed, putting a false "couldn't be reached" row into a history that is supposed to be
     * evidence.
     */
    private fun <T> Result<T>.rethrowCancellation(): Result<T> = also {
        (exceptionOrNull() as? CancellationException)?.let { throw it }
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "The directory couldn't be reached."

    class Factory(
        private val repo: HealthRepository,
        private val directory: ProviderDirectoryClient,
        private val images: CardImageStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoverageViewModel(repo, directory, images) as T
    }
}

/**
 * A policy as the add form has it, before it becomes a row. Its own type rather than fifteen
 * parameters, for the reason `MedicationDraft` is one: it travels from a dialog through the view
 * model to the repository, and two forms fill it in.
 */
data class PlanDraft(
    val carrierName: String,
    val planName: String? = null,
    val coverageKind: CoverageKind = CoverageKind.MEDICAL,
    val planType: PlanType = PlanType.OTHER,
    val groupNumber: String? = null,
    val payerId: String? = null,
    val rxBin: String? = null,
    val rxPcn: String? = null,
    val rxGroup: String? = null,
    val memberServicesPhone: String? = null,
    val nurseLinePhone: String? = null,
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val directoryUrl: String? = null,
    val note: String? = null
)

/** One person's place on a policy, as the form has it. */
data class MembershipDraft(
    val memberId: String? = null,
    val personCode: String? = null,
    val subscriberName: String? = null,
    val relationshipToSubscriber: String? = null,
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val primaryCoverage: Boolean = true,
    val note: String? = null
)

/** A doctor as the form has them. */
data class ProviderDraft(
    val name: String,
    val npi: String? = null,
    val specialty: String? = null,
    val practiceName: String? = null,
    val phone: String? = null,
    val addressLine: String? = null,
    val website: String? = null,
    val note: String? = null
)
