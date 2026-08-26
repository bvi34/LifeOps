package com.health.app.data.net

import com.health.app.logic.DirectoryOutcome
import com.health.app.logic.DirectoryPractitioner
import com.health.app.logic.DirectoryProbe
import com.health.app.logic.FhirDirectoryParser
import com.health.app.logic.ProviderDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The second — and last — class in Health that touches the network.
 *
 * It asks an **insurer's published provider directory** whether it lists a particular clinician. Since
 * the CMS Interoperability and Patient Access rule, payers publish that directory as a public,
 * unauthenticated FHIR R4 API (Da Vinci **PDEX Plan-Net**): no key, no sign-in, no member number, by
 * design, so that anybody can look a doctor up before booking.
 *
 * ### What leaves the device
 *
 * Exactly three things, and they are all facts about **a doctor or a URL**:
 *
 *  - the payer's own directory address, which the payer published;
 *  - a practitioner's **surname**, or
 *  - a practitioner's **NPI** — the national identifier that is printed on prescriptions, published
 *    in the federal NPPES registry, and searchable by anybody.
 *
 * What cannot leave, because there is no parameter and no code path that could carry it: a profile,
 * a name, a birth date, a member number, a diagnosis, a medicine, a reading. This class is handed a
 * base URL and a practitioner's identity and nothing else — [probe] takes a URL, [findPractitioner]
 * takes a URL, a name and an NPI. The payer learns that *somebody* asked its public directory about
 * one of its own listed clinicians, which is the question the directory exists to answer.
 *
 * That is the same test the drug lookup passes and states in [DrugLookupClient]: a question about a
 * **product** or a **practitioner** may cross the wire; a question about a **person in this
 * household** never does.
 *
 * ### Why the probe walks a list of URLs
 *
 * There is no national registry of these endpoints, and Health ships no table of guessed ones — see
 * `logic/ProviderDirectory` for why a wrong endpoint quietly answering "not listed" is the worst
 * possible outcome. So a probe tries the handful of paths a FHIR server conventionally lives at
 * underneath what the user pasted, stops at the first that answers a real `CapabilityStatement`, and
 * reports honestly when none does.
 *
 * Every call is on `Dispatchers.IO`, returns rather than throws for the outcomes that are answers
 * ("nothing published here" is an answer), and is only ever made because somebody pressed Check.
 * Nothing here runs on a timer, at startup, or in the background.
 *
 * Built on `HttpURLConnection` for the same reason as the other two clients in the suite: this is the
 * fourth small keyless GET, and it is still not worth an HTTP library.
 */
class ProviderDirectoryClient(
    private val userAgent: String = USER_AGENT
) {

    /** What a search found, and how sure the caller may be about it. */
    data class SearchResult(
        val practitioners: List<DirectoryPractitioner>,
        /** The URL that was asked, for the record kept against the check. */
        val queriedUrl: String,
        /** True when the search was by NPI, which is exact where a name search is a guess. */
        val byIdentifier: Boolean,
        /** A server's own complaint, where it made one instead of returning results. */
        val message: String? = null
    )

    /**
     * Find the payer's directory: try each candidate base in turn, ask for its `CapabilityStatement`,
     * and take the first that is really one.
     *
     * The outcome reported is the **most informative** failure rather than the last one. A run of
     * candidates that ends in three 404s after one 401 should say "that address wants a sign-in" —
     * which tells the user they have the member portal's URL — rather than "nothing published at
     * `…/plan-net/fhir`", which is a path Health invented and the user never typed.
     */
    suspend fun probe(rawUrl: String?): DirectoryProbe = withContext(Dispatchers.IO) {
        val candidates = ProviderDirectory.candidateBases(rawUrl)
        if (candidates.isEmpty()) {
            return@withContext DirectoryProbe(
                outcome = DirectoryOutcome.NOT_CONFIGURED,
                detail = "No directory address is recorded for this plan."
            )
        }

        var best: DirectoryProbe? = null
        for (base in candidates) {
            val response = fetch(ProviderDirectory.metadataUrl(base))
            val probe = when {
                response.body != null -> FhirDirectoryParser.parseCapability(response.body, base)
                else -> DirectoryProbe(
                    outcome = response.outcome,
                    baseUrl = base,
                    detail = response.detail
                )
            }
            if (probe.searchable) return@withContext probe
            if (best == null || rank(probe.outcome) < rank(best.outcome)) best = probe
        }
        best ?: DirectoryProbe(outcome = DirectoryOutcome.UNREACHABLE)
    }

    /**
     * Ask a directory about one practitioner.
     *
     * By NPI first, because that search can be definitive: the identifier is unique to one clinician
     * nationally, so a hit is the right person and a miss is a real miss. By surname only when there
     * is no NPI to use — and a name search is reported as what it is, so the caller can record
     * "several matched" rather than choosing one.
     *
     * The roles are a second, best-effort request. They carry the specialty, the practice and — the
     * fact that actually matters — the **networks** the practitioner is listed in, which is what
     * turns "this person exists in the directory" into "this person is in a network". A directory
     * that refuses the role search still yields a usable answer, minus the network names, and saying
     * so beats failing the whole check.
     */
    suspend fun findPractitioner(
        baseUrl: String,
        name: String?,
        npi: String?
    ): SearchResult = withContext(Dispatchers.IO) {
        val base = ProviderDirectory.normalizeBase(baseUrl)
            ?: throw LookupFailed("That directory address can't be read as a URL.")

        val identifier = ProviderDirectory.digits(npi)?.takeIf { ProviderDirectory.isValidNpi(it) }
        val url = when {
            identifier != null -> ProviderDirectory.npiSearchUrl(base, identifier)
            !name.isNullOrBlank() -> ProviderDirectory.nameSearchUrl(base, name)
            else -> throw LookupFailed("There is nothing to search for — add a name or an NPI first.")
        }

        val response = fetch(url)
        val body = response.body
            ?: throw LookupFailed(response.detail ?: "The directory couldn't be reached.")

        // A server that dislikes the request says so in an OperationOutcome rather than a Bundle.
        // "Unknown search parameter" is something the user can act on; reporting it as a network
        // failure would send them looking for a problem they don't have.
        FhirDirectoryParser.parseOperationOutcome(body)?.let { complaint ->
            return@withContext SearchResult(emptyList(), url, identifier != null, complaint)
        }

        val practitioners = FhirDirectoryParser.parsePractitioners(body)
        if (practitioners.isEmpty()) {
            return@withContext SearchResult(emptyList(), url, identifier != null)
        }

        val withRoles = practitioners.take(MAX_ROLE_LOOKUPS).flatMap { practitioner ->
            val roleUrl = ProviderDirectory.roleSearchUrl(base, practitioner.id)
            fetch(roleUrl).body?.let { FhirDirectoryParser.parseRoles(it) }.orEmpty()
        }

        SearchResult(
            practitioners = FhirDirectoryParser.merge(practitioners, withRoles),
            queriedUrl = url,
            byIdentifier = identifier != null
        )
    }

    /** Thrown only for the failures that are genuinely failures, never for "nothing matched". */
    class LookupFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Response(
        val outcome: DirectoryOutcome,
        val body: String?,
        val detail: String?
    )

    private fun fetch(urlString: String): Response {
        val conn = try {
            (URL(urlString).openConnection() as HttpURLConnection)
        } catch (_: Exception) {
            return Response(DirectoryOutcome.UNREACHABLE, null, "That address can't be opened.")
        }
        conn.apply {
            requestMethod = "GET"
            // FHIR's own media type first; some gateways serve plain JSON, so both are acceptable.
            setRequestProperty("Accept", "application/fhir+json, application/json")
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            val outcome = ProviderDirectory.classify(code)
            if (outcome != DirectoryOutcome.REACHABLE) {
                // Read the error body anyway: a FHIR server explains a 400 in an OperationOutcome,
                // and that explanation is more use than the status code on its own.
                val explanation = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { FhirDirectoryParser.parseOperationOutcome(it) }
                Response(outcome, null, explanation ?: describe(outcome, code))
            } else {
                val text = conn.inputStream.bufferedReader().use { reader ->
                    // A directory that answers with a megabyte of HTML is not a directory; cap the
                    // read rather than holding somebody's marketing page in memory. Read in a loop,
                    // because a single read on a network stream returns what has arrived so far,
                    // which for a bundle of twenty practitioners is reliably not all of it.
                    val builder = StringBuilder()
                    val buffer = CharArray(READ_CHUNK)
                    while (builder.length < MAX_BODY_CHARS) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        builder.append(buffer, 0, read)
                    }
                    builder.toString()
                }
                Response(DirectoryOutcome.REACHABLE, text, null)
            }
        } catch (_: IOException) {
            Response(DirectoryOutcome.UNREACHABLE, null, "Couldn't reach the directory — check the connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(outcome: DirectoryOutcome, code: Int): String = when (outcome) {
        DirectoryOutcome.NEEDS_SIGN_IN ->
            "That address asked for a sign-in. A plan's public directory shouldn't — this is " +
                "probably the member portal rather than the directory itself."
        DirectoryOutcome.NOT_PUBLISHED -> "Nothing is published at that address."
        DirectoryOutcome.RATE_LIMITED -> "The directory is asking us to slow down. Try again shortly."
        DirectoryOutcome.NOT_A_DIRECTORY -> "That address answered with HTTP $code rather than a directory."
        else -> "The directory answered with HTTP $code."
    }

    /**
     * Which failure is worth reporting when every candidate failed.
     *
     * Lower is more informative. A sign-in challenge tells the user something actionable about the
     * URL they pasted; "nothing published" at a path Health invented tells them nothing at all.
     */
    private fun rank(outcome: DirectoryOutcome): Int = when (outcome) {
        DirectoryOutcome.REACHABLE -> 0
        DirectoryOutcome.NEEDS_SIGN_IN -> 1
        DirectoryOutcome.NOT_A_DIRECTORY -> 2
        DirectoryOutcome.RATE_LIMITED -> 3
        DirectoryOutcome.UNREACHABLE -> 4
        DirectoryOutcome.NOT_PUBLISHED -> 5
        DirectoryOutcome.NOT_CONFIGURED -> 6
    }

    companion object {
        /** Directories, like the drug references, ask callers to identify themselves. Nothing user-specific is in it. */
        const val USER_AGENT = "LifeOps-Health/1.0 (https://github.com/bvi34/lifeops)"

        private const val TIMEOUT_MS = 15_000

        /** Enough for a bundle of twenty practitioners; far short of a marketing page. */
        private const val MAX_BODY_CHARS = 512 * 1024

        private const val READ_CHUNK = 8 * 1024

        /**
         * How many hits get their roles fetched. One request each, and a name search that matched
         * eight people does not deserve eight more requests to a stranger's server — the first few
         * are what a person will actually read.
         */
        private const val MAX_ROLE_LOOKUPS = 3
    }
}
