package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Providers, how a person is attached to one, and whether they were in network.
 */

/**
 * A doctor, dentist, therapist or practice the household sees.
 *
 * **Household-scoped, and deliberately not owned by an insurance plan.** That separation is the
 * whole point of the row. A doctor is a person you have a relationship with; a plan is a contract
 * you renew every January, and it is entirely ordinary for the plan to change while the doctor
 * doesn't. If the care team hung off the policy, changing carriers would mean re-entering every
 * clinician in the house — and, worse, would throw away the history of network checks that is the
 * only way to notice that the new plan doesn't cover the paediatrician the old one did.
 *
 * [npi] is the National Provider Identifier, and it is the single most valuable field here: it is
 * unique to one clinician nationally, so a directory search on it is exact where a name search is a
 * guess. It is validated (ten digits, Luhn over the `80840` prefix) before it is ever sent, because
 * a mistyped NPI and a doctor who has left the network both come back as no results.
 */
@Entity(tableName = "providers", indices = [Index("name"), Index("npi")])
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val npi: String?,
    val specialty: String?,
    /** The group or clinic they practise under — what a directory calls the organization. */
    val practiceName: String?,
    val phone: String?,
    val addressLine: String?,
    val website: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Which person sees which provider, and in what capacity.
 *
 * Its own table rather than a column on either side, because the relationship is many-to-many in
 * both directions and both directions actually happen: one paediatrician is primary for three
 * children, and one child has a paediatrician, a dentist and an allergist. [role] is *this person's*
 * relationship to that provider — the same clinician can be somebody's primary and somebody else's
 * specialist.
 */
@Entity(
    tableName = "provider_links",
    indices = [Index("profileId"), Index("providerId")]
)
data class ProviderLinkEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val providerId: String,
    /** [com.health.app.data.model.CareRole]'s key. */
    val role: String,
    /** ISO `yyyy-MM-dd` — since when they've been seeing them, when anybody knows. */
    val since: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One network check: what a directory said about one provider under one plan, at one moment.
 *
 * **Kept as an append-only history, never overwritten**, and that is the design rather than an
 * accident of it. A single "in network" flag cannot tell the difference between a doctor who was
 * never in the network and one who was in it until March, and that difference is the most useful
 * thing this feature produces. `logic/NetworkStatus` derives the verdict from the whole list; delete
 * the old rows and the verdict quietly degrades to "not listed" for both cases.
 *
 * A check with no [planId] is one made against no particular policy — which happens when somebody
 * rings the office and asks. The outcome column carries those too ([com.health.app.logic.CheckOutcome]),
 * because "a human was told this on the phone" is evidence, and evidence with a date on it belongs
 * in the same history as everything else.
 */
@Entity(
    tableName = "network_checks",
    indices = [Index("providerId"), Index("planId"), Index("checkedAt")]
)
data class NetworkCheckEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    /** Null for a check that wasn't about a specific policy — a phone call, usually. */
    val planId: String?,
    val checkedAt: Long,
    /** [com.health.app.logic.CheckOutcome]'s key. */
    val outcome: String,
    /** The carrier, as it read at the time. A household changes plans; the old checks stay true. */
    val directoryLabel: String?,
    val directoryUrl: String?,
    /** The name the directory had, when it differs from the one the household wrote down. */
    val matchedName: String?,
    val matchedNpi: String?,
    /** How many entries matched. More than one is the whole basis of the "couldn't tell them apart" verdict. */
    val matchCount: Int,
    /** The networks the listing named, comma-separated. Lists this short don't earn a table. */
    val networks: String?,
    val detail: String?
)
