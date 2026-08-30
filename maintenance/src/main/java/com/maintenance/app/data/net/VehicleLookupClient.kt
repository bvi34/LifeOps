package com.maintenance.app.data.net

import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.RecallsParser
import com.maintenance.app.logic.VehicleFacts
import com.maintenance.app.logic.Vin
import com.maintenance.app.logic.VpicParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The only class in Maintenance that touches the network.
 *
 * It talks to two public, keyless United States government references and nothing else:
 *
 *  - **vPIC** (`vpic.nhtsa.dot.gov`) — the VIN decoder. It turns eleven characters into a make,
 *    model, year, trim and engine, which is what chooses a maintenance schedule.
 *  - **NHTSA recalls** (`api.nhtsa.gov`) — open safety campaigns for a make, model and year.
 *
 * ### What leaves the device
 *
 * For a decode: **the first eleven characters of a VIN**, with the rest replaced by wildcards
 * (`1C4HJXDG5JW******`). Those eleven describe the *model* — world manufacturer, body, engine,
 * restraint system, check digit, model year, plant. The six that are dropped are the serial: the
 * number on your title, the one an insurer quotes, the one a vehicle-history service is keyed on.
 * They are dropped because they are the identifying half **and** because they are useless here —
 * vPIC returns exactly the same answer without them, which was checked against the live API. The
 * rule is [Vin.decodeQuery], a tested function rather than a habit.
 *
 * For recalls: **a make, a model and a year.** No VIN at all. The answer is identical for every 2018
 * Wrangler on the continent.
 *
 * So the test Health set for going online is met here too: *could this request tell anyone something
 * about a member of this household?* "What is a 2018 Wrangler Unlimited Sport?" is a question about
 * a product, of the kind anyone could type into a search engine. "Which truck is parked outside this
 * address?" is a question this class has no code path to ask.
 *
 * Every call is on `Dispatchers.IO`, throws [LookupException] on any failure, and happens only
 * because somebody pressed a button. Nothing here runs on a timer, at startup, or in the background.
 *
 * Built on `HttpURLConnection` rather than an HTTP library, mirroring LifeOps' `NwsClient`,
 * Logistics' `RecipeFetcher` and Health's `DrugLookupClient` — the suite has deliberately not taken
 * an OkHttp/Retrofit dependency for a handful of keyless GETs, and these are the next two.
 */
class VehicleLookupClient(
    private val vpicBaseUrl: String = VPIC_BASE,
    private val recallsBaseUrl: String = RECALLS_BASE
) {

    /** Any reason a lookup didn't produce an answer, with something a person can read. */
    class LookupException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Decode a VIN into the facts a schedule is chosen by.
     *
     * Throws [LookupException] when there is too little VIN to ask about, when the network fails, or
     * when vPIC answers with something that isn't a vehicle.
     */
    suspend fun decode(vin: String): VehicleFacts = withContext(Dispatchers.IO) {
        val query = Vin.decodeQuery(vin)
            ?: throw LookupException("That doesn't look like enough of a VIN to decode.")

        val body = get("$vpicBaseUrl/vehicles/DecodeVinValues/$query?format=json")
        VpicParser.parse(body)
            ?: throw LookupException("The decoder didn't recognise that VIN.")
    }

    /**
     * Open safety recalls for a model. An empty list is a real answer — most model years have none —
     * and is not an error.
     */
    suspend fun recalls(make: String, model: String, year: Int): List<Recall> = withContext(Dispatchers.IO) {
        val url = "$recallsBaseUrl/recalls/recallsByVehicle" +
            "?make=${make.encoded()}&model=${model.encoded()}&modelYear=$year"
        RecallsParser.parse(get(url))
    }

    private fun get(url: String): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw LookupException("Couldn't reach the lookup service.", e)
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw LookupException("The lookup service answered $code.")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: LookupException) {
            throw e
        } catch (e: Exception) {
            throw LookupException("Couldn't reach the lookup service.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun String.encoded(): String = URLEncoder.encode(trim(), "UTF-8")

    companion object {
        const val VPIC_BASE = "https://vpic.nhtsa.dot.gov/api"
        const val RECALLS_BASE = "https://api.nhtsa.gov"
        private const val TIMEOUT_MS = 15_000
    }
}
