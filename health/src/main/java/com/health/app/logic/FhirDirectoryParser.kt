package com.health.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure FHIR R4 parsing for a payer's provider directory — raw JSON in, [DirectoryProbe] and
 * [DirectoryPractitioner] out. No network and no Android, so every shape below is testable against a
 * captured payload; the HTTP hop lives in `data/net/ProviderDirectoryClient`. Same split, and same
 * reason, as [RxNormParser] and [OpenFdaParser]: somebody else's JSON is the part most likely to
 * surprise you, and it is worth being able to prove what happens to a surprising one without a
 * device and without a payer's server.
 *
 * Every entry point returns empty or null rather than throwing. A directory check is a convenience
 * over a doctor the household typed in themselves and can always ring; it must never be able to take
 * a screen down, and a payer serving HTML from its API path on a Sunday is a Tuesday occurrence.
 *
 * The three responses Health reads:
 *
 *  1. `GET {base}/metadata`                              → [parseCapability] — is this a directory?
 *  2. `GET {base}/Practitioner?identifier=…` / `?name=…` → [parsePractitioners]
 *  3. `GET {base}/PractitionerRole?practitioner=…`       → [parseRoles] — specialty, practice, network
 *
 * The third is where the useful part lives. Da Vinci **PDEX Plan-Net** models network membership on
 * `PractitionerRole.network` — a reference to the `Organization` that *is* the network — so "in
 * network" is a fact about a role, not about a person. A practitioner in a payer's directory may be
 * in three of its networks and not in yours, and Health keeps the network names precisely so it
 * never has to flatten that into an unqualified yes.
 */
object FhirDirectoryParser {

    private val gson = Gson()

    /**
     * The `CapabilityStatement` every FHIR server serves at `/metadata`, read for the two things
     * that matter: is it FHIR at all, and will it let you search for a practitioner.
     *
     * A body that parses but isn't a `CapabilityStatement` — a login page, an API gateway's error
     * envelope, a payer's marketing JSON — comes back as [DirectoryOutcome.NOT_A_DIRECTORY] rather
     * than as a reachable directory with nothing in it. The distinction is the whole point of asking:
     * "there is no directory here" and "the directory says your doctor isn't in it" must never be
     * reported as the same thing.
     */
    fun parseCapability(json: String, baseUrl: String): DirectoryProbe {
        val dto = read<CapabilityDto>(json)
            ?: return DirectoryProbe(
                outcome = DirectoryOutcome.NOT_A_DIRECTORY,
                baseUrl = baseUrl,
                detail = "That address answered with something that isn't FHIR."
            )

        if (!dto.resourceType.equals("CapabilityStatement", true) &&
            !dto.resourceType.equals("Conformance", true)
        ) {
            return DirectoryProbe(
                outcome = DirectoryOutcome.NOT_A_DIRECTORY,
                baseUrl = baseUrl,
                detail = dto.resourceType
                    ?.let { "That address is a FHIR server, but it answered with a $it rather than a capability statement." }
                    ?: "That address answered with something that isn't a FHIR capability statement."
            )
        }

        val resources = dto.rest.orEmpty()
            .flatMap { it.resource.orEmpty() }
            .mapNotNull { it.type?.trim()?.ifBlank { null } }
            .distinct()

        return DirectoryProbe(
            outcome = DirectoryOutcome.REACHABLE,
            baseUrl = baseUrl,
            softwareName = dto.software?.name?.trim()?.ifBlank { null },
            fhirVersion = dto.fhirVersion?.trim()?.ifBlank { null },
            resources = resources,
            detail = null
        )
    }

    /**
     * A `Bundle` of `Practitioner`s, flattened to the handful of facts a household needs: who the
     * directory thinks they are, their NPI if it published one, and whether it still calls them
     * active.
     *
     * Bundles carry entries of mixed types when `_include` is used, so anything that isn't a
     * `Practitioner` is skipped rather than mangled into one.
     */
    fun parsePractitioners(json: String): List<DirectoryPractitioner> {
        val bundle = read<BundleDto>(json) ?: return emptyList()
        return bundle.entry.orEmpty()
            .mapNotNull { it.resource }
            .filter { it.resourceType.equals("Practitioner", true) }
            .mapNotNull { it.toPractitioner() }
            .distinctBy { it.id }
    }

    /**
     * A `Bundle` of `PractitionerRole`s — the specialties, the practices, and the networks.
     *
     * Plan-Net's `network` is a plain FHIR reference, which in practice arrives one of three ways:
     * with a human-readable `display`, with only a `reference` like `Organization/aetna-ppo`, or as a
     * contained resource. Health takes the display where there is one and falls back to the last
     * segment of the reference, because "aetna-ppo" in front of somebody is worth more than a blank
     * where a network name should be — and much more than Health deciding the field was empty.
     */
    fun parseRoles(json: String): List<DirectoryRole> {
        val bundle = read<BundleDto>(json) ?: return emptyList()
        return bundle.entry.orEmpty()
            .mapNotNull { it.resource }
            .filter { it.resourceType.equals("PractitionerRole", true) }
            .map { resource ->
                DirectoryRole(
                    practitionerRef = resource.practitioner?.reference?.trim()?.ifBlank { null },
                    active = resource.active,
                    specialties = resource.specialty.orEmpty().mapNotNull { it.readable() },
                    organizations = listOfNotNull(resource.organization?.readable()),
                    locations = resource.location.orEmpty().mapNotNull { it.readable() },
                    networks = resource.network.orEmpty().mapNotNull { it.readable() }
                )
            }
    }

    /**
     * The `OperationOutcome` a FHIR server returns instead of results when it dislikes a request.
     *
     * Worth reading rather than discarding: "unknown search parameter" and "this endpoint is
     * retired" are things the user can act on, and reporting either of them as "couldn't reach the
     * directory" would send somebody looking for a network problem they don't have.
     */
    fun parseOperationOutcome(json: String): String? {
        val dto = read<OperationOutcomeDto>(json) ?: return null
        if (!dto.resourceType.equals("OperationOutcome", true)) return null
        return dto.issue.orEmpty()
            .mapNotNull { issue ->
                issue.diagnostics?.trim()?.ifBlank { null }
                    ?: issue.details?.text?.trim()?.ifBlank { null }
                    ?: issue.code?.trim()?.ifBlank { null }
            }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }
    }

    /**
     * Fold the roles into the practitioners they belong to, so one entry carries everything the
     * directory said about one person.
     *
     * Roles reference a practitioner as `Practitioner/{id}`; matching is on that last segment, and a
     * role whose reference matches nothing in the bundle is dropped rather than attached to whoever
     * happens to be first.
     */
    fun merge(
        practitioners: List<DirectoryPractitioner>,
        roles: List<DirectoryRole>
    ): List<DirectoryPractitioner> = practitioners.map { practitioner ->
        val mine = roles.filter { role ->
            role.practitionerRef?.substringAfterLast('/')?.equals(practitioner.id, true) == true
        }
        if (mine.isEmpty()) practitioner
        else practitioner.copy(
            specialties = (practitioner.specialties + mine.flatMap { it.specialties }).distinct(),
            organizations = (practitioner.organizations + mine.flatMap { it.organizations }).distinct(),
            networks = (practitioner.networks + mine.flatMap { it.networks }).distinct()
        )
    }

    private fun ResourceDto.toPractitioner(): DirectoryPractitioner? {
        val id = id?.trim()?.ifBlank { null } ?: return null
        return DirectoryPractitioner(
            id = id,
            name = name.orEmpty().firstNotNullOfOrNull { it.readable() },
            npi = identifier.orEmpty()
                .firstOrNull { it.system?.contains("us-npi", true) == true }
                ?.value?.trim()?.ifBlank { null }
                ?: identifier.orEmpty().firstOrNull { ProviderDirectory.isValidNpi(it.value) }?.value?.trim(),
            active = active,
            specialties = emptyList(),
            organizations = emptyList(),
            networks = emptyList()
        )
    }

    /** `HumanName` → "Jane A Okafor", preferring the server's own rendering when it gave one. */
    private fun HumanNameDto.readable(): String? {
        text?.trim()?.ifBlank { null }?.let { return it }
        val parts = given.orEmpty().mapNotNull { it.trim().ifBlank { null } } +
            listOfNotNull(family?.trim()?.ifBlank { null })
        return parts.joinToString(" ").ifBlank { null }
    }

    /** `CodeableConcept` → the words a person would read, never the code on its own if text exists. */
    private fun CodeableConceptDto.readable(): String? {
        text?.trim()?.ifBlank { null }?.let { return it }
        return coding.orEmpty().firstNotNullOfOrNull { coding ->
            coding.display?.trim()?.ifBlank { null } ?: coding.code?.trim()?.ifBlank { null }
        }
    }

    /** `Reference` → its display, or the id at the end of the reference as a last resort. */
    private fun ReferenceDto.readable(): String? =
        display?.trim()?.ifBlank { null }
            ?: reference?.substringAfterLast('/')?.trim()?.ifBlank { null }

    private inline fun <reified T> read(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: IllegalStateException) {
        // Gson throws this rather than JsonSyntaxException when the JSON is valid but the shape is
        // wrong for the target type — an array where an object was expected, which is what a payer's
        // error gateway sends. Same answer: nothing was parsed.
        null
    }

    // --- wire shapes -------------------------------------------------------------------------------
    // Deliberately lenient: every field nullable, nothing required. FHIR omits whole elements that
    // have nothing to say, servers differ in which optional ones they populate, and a parser that
    // insists on any of them fails on the ordinary case.

    private data class CapabilityDto(
        val resourceType: String?,
        val fhirVersion: String?,
        val software: SoftwareDto?,
        val rest: List<RestDto>?
    )

    private data class SoftwareDto(val name: String?, val version: String?)
    private data class RestDto(val mode: String?, val resource: List<RestResourceDto>?)
    private data class RestResourceDto(val type: String?)

    private data class BundleDto(val resourceType: String?, val total: Int?, val entry: List<EntryDto>?)
    private data class EntryDto(val fullUrl: String?, val resource: ResourceDto?)

    private data class ResourceDto(
        val resourceType: String?,
        val id: String?,
        val active: Boolean?,
        val name: List<HumanNameDto>?,
        val identifier: List<IdentifierDto>?,
        val practitioner: ReferenceDto?,
        val organization: ReferenceDto?,
        val specialty: List<CodeableConceptDto>?,
        val location: List<ReferenceDto>?,
        val network: List<ReferenceDto>?
    )

    private data class HumanNameDto(
        val text: String?,
        val family: String?,
        val given: List<String>?,
        val prefix: List<String>?,
        val suffix: List<String>?
    )

    private data class IdentifierDto(val system: String?, val value: String?)
    private data class ReferenceDto(val reference: String?, val display: String?)
    private data class CodeableConceptDto(val text: String?, val coding: List<CodingDto>?)
    private data class CodingDto(val system: String?, val code: String?, val display: String?)

    private data class OperationOutcomeDto(val resourceType: String?, val issue: List<IssueDto>?)
    private data class IssueDto(
        val severity: String?,
        val code: String?,
        val diagnostics: String?,
        val details: CodeableConceptDto?
    )
}

/**
 * One `PractitionerRole` — a practitioner as they work at one place, under one specialty, in one set
 * of networks. Kept separate from [DirectoryPractitioner] because the same clinician holds several,
 * and flattening them at parse time is what loses the fact that only *one* of them is in your plan's
 * network.
 */
data class DirectoryRole(
    val practitionerRef: String?,
    val active: Boolean?,
    val specialties: List<String> = emptyList(),
    val organizations: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val networks: List<String> = emptyList()
)
