package com.health.app.data.net

import com.health.app.logic.DrugCandidate
import com.health.app.logic.DrugMonograph
import com.health.app.logic.DrugSources
import com.health.app.logic.OpenFdaParser
import com.health.app.logic.RxNormParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The only class in Health that touches the network.
 *
 * It talks to two public, keyless United States government references and nothing else:
 *
 *  - **RxNorm** (`rxnav.nlm.nih.gov`, National Library of Medicine) — the drug vocabulary. It turns
 *    "childrens tylenol" into a concept id, and that id into ingredients, strengths and a form.
 *  - **openFDA** (`api.fda.gov`) — the Structured Product Label, the text printed on the box.
 *
 * ### What leaves the device
 *
 * Exactly one thing: **the search term the user typed, or an RxNorm concept id.** No profile, no
 * name, no age, no temperature, no dose history, no device identifier — there is no code path in
 * this file that can reach any of them, because the only inputs are a query string and an id.
 *
 * That constraint is why Health's manifest could go from "no permissions at all" to holding
 * `INTERNET` without the module's promise changing. Household medical records still cannot leave
 * this device. What crosses the wire is a question about a *product*, of the kind anyone could type
 * into a search engine, and the answer is cached locally so the same question is not asked twice.
 *
 * Every call is on `Dispatchers.IO`, throws [LookupException] on any failure, and is only ever made
 * because somebody pressed Search or Refresh. Nothing here runs on a timer, at startup, or in the
 * background: a lookup is a thing the user asked for, once.
 *
 * Built on `HttpURLConnection` rather than an HTTP library, mirroring LifeOps' `NwsClient` and
 * Logistics' `RecipeFetcher` — the suite has deliberately not taken an OkHttp/Retrofit dependency
 * for three small keyless GETs, and this is the third.
 */
class DrugLookupClient(
    private val rxNormBaseUrl: String = RXNORM_BASE,
    private val openFdaBaseUrl: String = OPENFDA_BASE,
    private val userAgent: String = USER_AGENT
) {

    class LookupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Find products matching what the user typed.
     *
     * Two passes, and the second only runs if the first found nothing: RxNorm's exact-name search
     * first, then its spelling-tolerant one for the "tylonol" case. The approximate path returns
     * concept ids that may arrive without names, so the first few are resolved individually — capped
     * at [MAX_NAME_RESOLUTIONS], because a search that fires fifteen requests to fill in a list
     * nobody scrolls to the bottom of is a search that feels broken.
     */
    suspend fun search(term: String, limit: Int = 20): List<DrugCandidate> = withContext(Dispatchers.IO) {
        val query = term.trim()
        if (query.length < MIN_QUERY_LENGTH) return@withContext emptyList()

        val exact = RxNormParser.parseDrugs(get("$rxNormBaseUrl/drugs.json?name=${encode(query)}"))
        if (exact.isNotEmpty()) return@withContext exact.take(limit)

        val approximate = RxNormParser
            .parseApproximate(
                get("$rxNormBaseUrl/approximateTerm.json?term=${encode(query)}&maxEntries=$limit")
            )
            .take(limit)

        // Fill in the names the approximate endpoint didn't carry; drop anything still nameless
        // rather than showing somebody a bare concept id and asking them to pick.
        var resolutions = 0
        approximate.mapNotNull { candidate ->
            if (candidate.name.isNotBlank()) return@mapNotNull candidate
            if (resolutions >= MAX_NAME_RESOLUTIONS) return@mapNotNull null
            resolutions++
            runCatching { resolveName(candidate.rxcui) }.getOrNull()
                ?.let { resolved -> candidate.copy(name = resolved.name, tty = resolved.tty) }
        }
    }

    /**
     * Everything Health caches about one product, assembled from up to four requests: RxNorm's own
     * properties, its attributes, its related concepts, and openFDA's label.
     *
     * Only the first is required. RxNorm's own name for the concept is the one fact without which
     * there is nothing to show; each of the other three is wrapped so that a product with no
     * attributes, no related concepts or — very commonly — no openFDA label still produces a
     * monograph carrying what *was* found. A partial answer here is the normal case, not an error:
     * plenty of real products have a sparse entry in one reference and a full one in the other.
     */
    suspend fun fetchMonograph(rxcui: String, nowMillis: Long): DrugMonograph =
        withContext(Dispatchers.IO) {
            val id = rxcui.trim()
            if (id.isEmpty()) throw LookupException("No RxNorm id to look up")

            val concept = resolveName(id)
            val attributes = runCatching {
                RxNormParser.parseAttributes(
                    get("$rxNormBaseUrl/rxcui/${encode(id)}/allProperties.json?prop=attributes")
                )
            }.getOrDefault(RxNormParser.Attributes())
            val related = runCatching {
                RxNormParser.parseRelated(
                    get("$rxNormBaseUrl/rxcui/${encode(id)}/related.json?tty=IN+PIN+BN+DF")
                )
            }.getOrDefault(RxNormParser.Related())

            val base = DrugMonograph(
                rxcui = id,
                name = concept.name,
                genericName = related.ingredients.firstOrNull(),
                brandName = related.brandNames.firstOrNull(),
                doseForm = related.doseForms.firstOrNull(),
                ingredients = related.ingredients,
                availableStrengths = attributes.availableStrengths,
                schedule = attributes.schedule,
                sources = listOf(DrugSources.RXNORM),
                fetchedAt = nowMillis
            )

            runCatching { fetchLabel(id, base) }.getOrDefault(base)
        }

    /**
     * The openFDA label for a product, tried the two ways it can be found.
     *
     * By RxNorm id first, which is exact. Failing that, by brand or generic name — openFDA indexes
     * `openfda.rxcui` from the same crosswalk RxNorm publishes, but the coverage is not total, and a
     * name search finds the label for plenty of products the id search misses.
     *
     * A 404 from openFDA means "no label matched", which is an ordinary answer rather than a
     * failure, so it returns [base] unchanged instead of throwing.
     */
    private fun fetchLabel(rxcui: String, base: DrugMonograph): DrugMonograph {
        val byId = getOrNull("$openFdaBaseUrl/drug/label.json?search=openfda.rxcui:%22$rxcui%22&limit=1")
        if (byId != null) {
            val merged = OpenFdaParser.parseLabel(byId, base)
            if (merged.hasLabel) return merged
        }

        val name = (base.brandName ?: base.genericName ?: base.name).trim()
        if (name.isEmpty()) return base
        val quoted = encode("\"$name\"")
        val byName = getOrNull(
            "$openFdaBaseUrl/drug/label.json" +
                "?search=openfda.brand_name:$quoted+openfda.generic_name:$quoted&limit=1"
        ) ?: return base
        return OpenFdaParser.parseLabel(byName, base)
    }

    /** One concept's own name and term type. The one call a monograph cannot do without. */
    private fun resolveName(rxcui: String): DrugCandidate {
        val json = get("$rxNormBaseUrl/rxcui/${encode(rxcui)}/properties.json")
        return RxNormParser.parseProperties(json)
            ?: throw LookupException("RxNorm has no concept $rxcui")
    }

    /** As [get], but a "nothing matched" answer comes back as null rather than as an exception. */
    private fun getOrNull(urlString: String): String? = try {
        get(urlString)
    } catch (_: LookupException) {
        null
    }

    private fun get(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw LookupException("Nothing found")
            }
            if (code !in 200..299) {
                throw LookupException("The drug reference answered with HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: LookupException) {
            throw e
        } catch (e: IOException) {
            throw LookupException("Couldn't reach the drug reference — check the connection", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val RXNORM_BASE = "https://rxnav.nlm.nih.gov/REST"
        const val OPENFDA_BASE = "https://api.fda.gov"

        /** Both services ask callers to identify themselves. Nothing user-specific is in it. */
        const val USER_AGENT = "LifeOps-Health/1.0 (https://github.com/bvi34/lifeops)"

        private const val TIMEOUT_MS = 15_000

        /** Below this, a search matches half the pharmacopoeia and helps nobody. */
        const val MIN_QUERY_LENGTH = 3

        private const val MAX_NAME_RESOLUTIONS = 5
    }
}
