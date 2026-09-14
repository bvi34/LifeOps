package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.ProviderEntity
import com.health.app.data.db.entities.ProviderLinkEntity
import com.health.app.data.model.CareRole
import com.health.app.data.model.CareTeamMember
import com.health.app.data.model.Provider
import com.health.app.data.model.ProviderLink
import com.health.app.logic.NetworkStatus
import com.health.app.logic.ProviderDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Providers, and how each person is attached to them.
 *
 * A provider is household-scoped and the link to a person is not, the same shape [CabinetStore]
 * uses for a bottle and [DoseStore] for a dose of it.
 */
class CareTeamStore(
    private val dao: HealthDao
) {

    fun observeProviders(): Flow<List<Provider>> =
        dao.observeProviders().map { rows -> rows.map { it.toModel() } }

    suspend fun getProvider(id: String): Provider? = dao.getProvider(id)?.toModel()

    /**
     * One person's care team, each member carrying where they stand against *that person's* coverage.
     *
     * The verdict is derived from the checks that are actually about this person's cover: the ones
     * made under a policy they are on, plus the ones made under no policy at all — a phone call to
     * the office is evidence whoever is asking. A check made under a policy the household has since
     * left is deliberately **not** counted: last year's network is not this year's, and letting an
     * old carrier's yes stand under a new plan would be the most convincing wrong answer this feature
     * could produce. Those checks are not deleted; they simply stop speaking for a plan they were
     * never about, and reappear the moment somebody is put back on that policy.
     *
     * Sorted by verdict — anything that needs a phone call first, then the unanswered, then the
     * settled — and by role within it, so "who is her GP" stays a glance rather than a search.
     */
    fun observeCareTeam(profileId: String): Flow<List<CareTeamMember>> =
        combine(
            dao.observeProviders(),
            dao.observeProviderLinks(profileId),
            dao.observeNetworkChecks(),
            dao.observeInsuranceMembers(profileId)
        ) { providers, links, checks, memberships ->
            val nowMillis = now()
            val providersById = providers.associateBy { it.id }
            val myPlanIds = memberships.map { it.planId }.toSet()
            val relevant = checks.filter { it.planId == null || it.planId in myPlanIds }
                .groupBy { it.providerId }

            links.mapNotNull { link ->
                val provider = providersById[link.providerId] ?: return@mapNotNull null
                val mine = relevant[link.providerId].orEmpty().map { it.toModel() }
                CareTeamMember(
                    provider = provider.toModel(),
                    link = link.toModel(),
                    network = NetworkStatus.evaluate(mine.map { it.toRecord() }, nowMillis),
                    checks = mine.sortedBy { it.checkedAt }
                )
            }.sortedWith(
                compareBy(
                    { it.network.verdict.sortRank },
                    { it.link.role.sortRank },
                    { it.provider.name.lowercase() }
                )
            )
        }

    /**
     * Add a provider, and optionally put them on somebody's care team in the same gesture — the way
     * it happens when a receptionist hands over a card at the end of an appointment.
     *
     * Only the name is required. Everything else, the NPI included, can be filled in later or never;
     * a form that demands a national provider identifier before it will save is a form that gets
     * abandoned, and a doctor with only a name and a phone number in the app is already worth having.
     */
    suspend fun addProvider(
        name: String,
        npi: String? = null,
        specialty: String? = null,
        practiceName: String? = null,
        phone: String? = null,
        addressLine: String? = null,
        website: String? = null,
        note: String? = null,
        forProfileId: String? = null,
        role: CareRole = CareRole.PRIMARY
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertProvider(
            ProviderEntity(
                id = id,
                name = name.trim(),
                npi = ProviderDirectory.digits(npi),
                specialty = specialty.clean(),
                practiceName = practiceName.clean(),
                phone = phone.clean(),
                addressLine = addressLine.clean(),
                website = website.clean(),
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        forProfileId?.let { linkProvider(it, id, role) }
        return id
    }

    suspend fun updateProvider(provider: Provider) {
        val existing = dao.getProvider(provider.id) ?: return
        dao.upsertProvider(
            existing.copy(
                name = provider.name.trim(),
                npi = ProviderDirectory.digits(provider.npi),
                specialty = provider.specialty.clean(),
                practiceName = provider.practiceName.clean(),
                phone = provider.phone.clean(),
                addressLine = provider.addressLine.clean(),
                website = provider.website.clean(),
                note = provider.note.clean(),
                updatedAt = now()
            )
        )
    }

    /**
     * Remove a provider from the household, along with everybody who saw them and every check made
     * about them.
     *
     * The one deletion in this repository that genuinely destroys history, and it is the right call:
     * a network check is evidence *about a provider*, so with the provider gone it is evidence about
     * nothing. That is not true of a dose, which happened to a person and stays whatever becomes of
     * the medicine.
     */
    suspend fun deleteProvider(providerId: String) = dao.deleteProviderCascade(providerId)

    /** Put a provider on somebody's care team. Returns the link, so the screen can edit it straight away. */
    suspend fun linkProvider(
        profileId: String,
        providerId: String,
        role: CareRole = CareRole.PRIMARY,
        since: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertProviderLink(
            ProviderLinkEntity(
                id = id,
                profileId = profileId,
                providerId = providerId,
                role = role.key,
                since = since.clean(),
                note = note.clean(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateProviderLink(link: ProviderLink) {
        val existing = dao.getProviderLink(link.id) ?: return
        dao.upsertProviderLink(
            existing.copy(
                role = link.role.key,
                since = link.since.clean(),
                note = link.note.clean(),
                updatedAt = now()
            )
        )
    }

    /**
     * Take a provider off one person's care team, keeping the provider — and keeping every check made
     * about them, which still speaks for everybody else in the house who sees them.
     */
    suspend fun unlinkProvider(linkId: String) = dao.deleteProviderLink(linkId)
}
