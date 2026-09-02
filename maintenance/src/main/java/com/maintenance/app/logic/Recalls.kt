package com.maintenance.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A safety recall on a vehicle, as NHTSA publishes it.
 *
 * Recalls sit beside upkeep and cover on the docket because they are the third kind of thing a
 * vehicle can owe you: not a job that comes round, not paperwork that expires, but a defect the
 * manufacturer has admitted to and will fix for nothing. The reason they belong in this app at all
 * is that nobody goes looking for them — the letter arrives at whatever address the DMV last had.
 *
 * [parkIt] and [parkOutside] are NHTSA's two "stop what you are doing" flags: *do not drive* and
 * *do not park indoors*. They are rare and they are the reason this is worth surfacing at all, so
 * they are carried as their own fields rather than left inside the prose.
 */
data class Recall(
    val campaignNumber: String,
    val component: String,
    val summary: String,
    val consequence: String? = null,
    val remedy: String? = null,
    val manufacturer: String? = null,
    val reportedOn: LocalDate? = null,
    val parkIt: Boolean = false,
    val parkOutside: Boolean = false
) {
    /** The two flags are an emergency; everything else is a job to book. */
    val isUrgent: Boolean get() = parkIt || parkOutside

    /** "Do not drive · Do not park indoors · Seat belts" — what the row leads with. */
    val headline: String
        get() = listOfNotNull(
            "Do not drive".takeIf { parkIt },
            "Do not park indoors".takeIf { parkOutside },
            component.titleCaseComponent().takeIf { it.isNotBlank() }
        ).joinToString(" · ")
}

/**
 * How often a vehicle's recall list is worth asking about again, and when the last answer has aged
 * out.
 *
 * Every other line on the docket is owed because *your* vehicle changed — miles went on it, a policy
 * ran out. A recall list is the one that goes stale while the vehicle sits still: the manufacturer
 * admits to a defect years after the car was built, and nobody tells you, because the letter goes to
 * whatever address the DMV last had. So the answer has a shelf life, and this is where its length is
 * written down — once, read both by the schedule packs (which turn it into a recurring prompt) and
 * by the vehicle screen (which says out loud when the answer on file is old).
 *
 * Six months is a compromise with nothing clever behind it: campaigns are opened continuously, and a
 * check costs one keyless request to a public API. Twice a year is often enough that nothing sits
 * unknown for long and rare enough that the prompt never becomes wallpaper.
 */
object RecallChecks {

    const val EVERY_DAYS = 180

    /** The same cadence in the words a person uses. Keep it saying what [EVERY_DAYS] counts. */
    const val CADENCE = "every 6 months"

    private const val DAY_MILLIS = 86_400_000L

    /**
     * Whether the answer on file is old enough to be worth asking again.
     *
     * **Never asked counts as stale**, which is the case that matters most: a vehicle nobody has
     * ever checked is exactly the one carrying a decade of open campaigns.
     */
    fun isStale(checkedAt: Long?, now: Long): Boolean =
        checkedAt == null || now - checkedAt >= EVERY_DAYS * DAY_MILLIS
}

/**
 * Pure parsing of `api.nhtsa.gov/recalls/recallsByVehicle`. No network, no Android.
 *
 * The query is make, model and year — **no VIN** — so this whole feature costs nothing in privacy:
 * the question is "what has been recalled on a 2018 Wrangler", which is a question about a model,
 * and the answer is the same for every one of them.
 */
object RecallsParser {

    private val REPORTED = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun parse(json: String): List<Recall> {
        val response = try {
            Gson().fromJson(json, RecallsResponse::class.java)
        } catch (_: JsonSyntaxException) {
            return emptyList()
        } ?: return emptyList()

        return response.results.orEmpty().mapNotNull { row ->
            val campaign = row.NHTSACampaignNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Recall(
                campaignNumber = campaign,
                component = row.Component?.trim().orEmpty(),
                summary = row.Summary?.trim().orEmpty(),
                consequence = row.Consequence?.trim()?.takeIf { it.isNotEmpty() },
                remedy = row.Remedy?.trim()?.takeIf { it.isNotEmpty() },
                manufacturer = row.Manufacturer?.trim()?.takeIf { it.isNotEmpty() },
                reportedOn = row.ReportReceivedDate?.trim()?.let {
                    runCatching { LocalDate.parse(it, REPORTED) }.getOrNull()
                },
                parkIt = row.parkIt.isTrue(),
                parkOutside = row.parkOutSide.isTrue()
            )
        // Newest first, and the two that say "stop driving" above everything regardless of date.
        }.sortedWith(compareByDescending<Recall> { it.isUrgent }.thenByDescending { it.reportedOn })
    }

    /** NHTSA sends these as the strings "True"/"False", not as booleans. */
    private fun String?.isTrue(): Boolean = this?.trim().equals("true", ignoreCase = true)

    private data class RecallsResponse(val results: List<RecallRow>?)

    @Suppress("PropertyName")
    private data class RecallRow(
        val NHTSACampaignNumber: String? = null,
        val Component: String? = null,
        val Summary: String? = null,
        val Consequence: String? = null,
        val Remedy: String? = null,
        val Manufacturer: String? = null,
        val ReportReceivedDate: String? = null,
        val parkIt: String? = null,
        val parkOutSide: String? = null
    )
}

/** "SEAT BELTS:FRONT:ANCHORAGE" → "Seat belts · Front · Anchorage". */
internal fun String.titleCaseComponent(): String =
    split(':')
        .map { part -> part.trim().lowercase().replaceFirstChar { it.titlecase() } }
        .filter { it.isNotBlank() }
        .joinToString(" · ")
