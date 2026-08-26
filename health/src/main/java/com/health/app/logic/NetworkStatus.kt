package com.health.app.logic

import kotlin.math.abs

/**
 * Reading a doctor's network status **out of the history of every check**, rather than out of the
 * last one.
 *
 * This is the part of the feature that earns its keep, and it exists because the honest answer to
 * "is this doctor in network?" is almost never a plain yes or no. A directory can say:
 *
 *  - *here they are* — the good case, and still only as good as the directory;
 *  - *they aren't here* — which means something completely different depending on whether an earlier
 *    check found them. **Not in today's directory but in March's is a doctor who has left the
 *    network**, and that is a phone call to make before the appointment. Not in today's and not in
 *    any before it is a doctor who was probably never in it, which is a different conversation;
 *  - *here are four people with that name* — no answer at all, and one that must not be rounded up
 *    into a yes;
 *  - *nothing, the server is down* — no answer either, and one that must not overwrite the last real
 *    one.
 *
 * A single stored "in network" flag can't tell those apart, which is why Health keeps every check as
 * a row and derives the verdict here. It is also why nothing in this file ever *deletes* a check:
 * the evidence that somebody used to be listed is the whole basis of the most useful verdict of the
 * set.
 *
 * Framework-free and unit-tested, against an injected clock like the rest of `logic/`.
 */

/** What one check found, at the moment it ran. */
enum class CheckOutcome(val key: String, val label: String) {
    /** The directory listed them. */
    LISTED("listed", "Listed"),
    /** The directory answered, and they were not in it. */
    NOT_LISTED("not_listed", "Not listed"),
    /** Several entries matched the name and nothing distinguished them. Not an answer. */
    AMBIGUOUS("ambiguous", "Several matches"),
    /** No directory to ask, or it couldn't be reached. Also not an answer. */
    UNAVAILABLE("unavailable", "Directory unavailable"),
    /** Somebody rang the office or the insurer and was told yes. */
    CONFIRMED_BY_HAND("confirmed_by_hand", "Confirmed by phone"),
    /** Somebody rang and was told no. Worth recording; it is the answer that costs money to learn late. */
    DECLINED_BY_HAND("declined_by_hand", "Told not in network");

    /** Whether this outcome actually decides anything, as against merely happening. */
    val decisive: Boolean
        get() = this == LISTED || this == NOT_LISTED || this == CONFIRMED_BY_HAND || this == DECLINED_BY_HAND

    companion object {
        fun fromKey(key: String?): CheckOutcome = entries.firstOrNull { it.key == key } ?: UNAVAILABLE
    }
}

/** One check, as the history keeps it. The database row carries more; this is what the verdict needs. */
data class NetworkCheckRecord(
    val checkedAt: Long,
    val outcome: CheckOutcome,
    /** Which directory answered — a plan can change carriers, and the old answers stay in the history. */
    val directoryLabel: String? = null,
    /** The name the directory had, when it isn't quite the name that was written down. */
    val matchedName: String? = null,
    /** The networks the directory listed them under, where it named them. */
    val networks: List<String> = emptyList(),
    val detail: String? = null
)

/** Where a verdict stands, spelled out. */
enum class NetworkVerdict(val label: String) {
    /** Today's directory lists them. The strongest thing Health will say, and it still says which network. */
    IN_NETWORK("In network"),

    /**
     * Listed once, not listed now. The verdict this whole file exists for: a doctor who has left the
     * network looks identical to one who was never in it unless somebody kept the earlier answer.
     */
    DROPPED("Was listed — not any more"),

    /** Checked, and never found in any directory Health has asked. */
    NEVER_LISTED("Never listed"),

    /** More than one person matched and nothing settled it. An NPI would. */
    AMBIGUOUS("Couldn't tell them apart"),

    /** Somebody rang and was told yes. A person's word, recorded as a person's word. */
    CONFIRMED_BY_PHONE("Confirmed by phone"),

    /** Somebody rang and was told no. */
    DECLINED("Told not in network"),

    /** There is a plan, but no directory that could be asked. */
    UNAVAILABLE("Couldn't check"),

    /** Nobody has ever checked. Health has no opinion, and says so. */
    UNCHECKED("Not checked");

    /** Whether this is something the care-team list should be saying out loud. */
    val needsAttention: Boolean
        get() = this == DROPPED || this == NEVER_LISTED || this == DECLINED

    /** Trouble first, then the unanswered, then the settled — the order to read a care team in. */
    val sortRank: Int
        get() = when (this) {
            DROPPED -> 0
            DECLINED -> 1
            NEVER_LISTED -> 2
            AMBIGUOUS -> 3
            UNAVAILABLE -> 4
            UNCHECKED -> 5
            CONFIRMED_BY_PHONE -> 6
            IN_NETWORK -> 7
        }
}

/** The verdict with the evidence behind it — what was found, when, and whether to ask again. */
data class NetworkAssessment(
    val verdict: NetworkVerdict,
    val summary: String,
    /** When Health last got any answer at all, including "couldn't reach it". */
    val lastCheckedAt: Long?,
    /** When a directory last actually listed them. What makes [NetworkVerdict.DROPPED] sayable. */
    val lastListedAt: Long?,
    /** The networks the most recent listing named, where it named any. */
    val networks: List<String> = emptyList(),
    /** Old enough to be worth asking again. Directories change monthly; a year-old yes is a guess. */
    val stale: Boolean = false
) {
    val needsAttention: Boolean get() = verdict.needsAttention
}

object NetworkStatus {

    /**
     * How long a directory answer is worth trusting before Health starts suggesting a fresh check.
     *
     * Payers are required to keep these directories current and are audited on how badly they manage
     * it; entries go stale in weeks, not years. Six weeks is about one billing cycle — long enough
     * not to nag, short enough that an answer from before the last renewal is flagged as what it is.
     */
    const val STALE_AFTER_DAYS: Long = 45L

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Read the history and say where this doctor stands.
     *
     * The rules, in the order they apply:
     *
     *  1. **The most recent decisive check wins.** A directory that couldn't be reached this morning
     *     does not overwrite the answer it gave last month — an outage is not evidence about a
     *     doctor, and treating it as one would flip a whole care team to "unknown" every time a
     *     payer's server hiccuped.
     *  2. **A "not listed" is read against everything before it.** If any earlier check found them,
     *     the verdict is [NetworkVerdict.DROPPED] rather than [NetworkVerdict.NEVER_LISTED], because
     *     those are different facts and only one of them means "ring the office".
     *  3. **A phone confirmation is kept as a phone confirmation.** It never becomes
     *     [NetworkVerdict.IN_NETWORK]: somebody was told something once, which is worth recording and
     *     is not the same as a published listing.
     *  4. **Ambiguity is never rounded.** Four Dr Patels is not a yes.
     */
    fun evaluate(checks: List<NetworkCheckRecord>, nowMillis: Long): NetworkAssessment {
        if (checks.isEmpty()) {
            return NetworkAssessment(
                verdict = NetworkVerdict.UNCHECKED,
                summary = "Nobody has checked this doctor against the plan's directory yet.",
                lastCheckedAt = null,
                lastListedAt = null
            )
        }

        val ordered = checks.sortedBy { it.checkedAt }
        val latest = ordered.last()
        val lastDecisive = ordered.lastOrNull { it.outcome.decisive }
        val lastListing = ordered.lastOrNull { it.outcome == CheckOutcome.LISTED }
        val everListed = lastListing != null

        // The check the verdict rests on: the last one that decided anything, or — when nothing ever
        // has — the last thing that happened, so an unreachable directory still reports itself.
        val basis = lastDecisive ?: latest
        val stale = ageDays(basis.checkedAt, nowMillis) > STALE_AFTER_DAYS

        val verdict = when (basis.outcome) {
            CheckOutcome.LISTED -> NetworkVerdict.IN_NETWORK
            CheckOutcome.NOT_LISTED -> if (everListed) NetworkVerdict.DROPPED else NetworkVerdict.NEVER_LISTED
            CheckOutcome.CONFIRMED_BY_HAND -> NetworkVerdict.CONFIRMED_BY_PHONE
            CheckOutcome.DECLINED_BY_HAND -> NetworkVerdict.DECLINED
            CheckOutcome.AMBIGUOUS -> NetworkVerdict.AMBIGUOUS
            CheckOutcome.UNAVAILABLE -> NetworkVerdict.UNAVAILABLE
        }

        // Ambiguity in the newest check is worth surfacing even when an older check decided the
        // matter — the directory has since grown a second person with that name, and the old yes may
        // have been about either of them.
        val effective =
            if (latest.outcome == CheckOutcome.AMBIGUOUS && verdict != NetworkVerdict.DROPPED) {
                NetworkVerdict.AMBIGUOUS
            } else {
                verdict
            }

        return NetworkAssessment(
            verdict = effective,
            summary = summarize(effective, basis, latest, lastListing, nowMillis, stale),
            lastCheckedAt = latest.checkedAt,
            lastListedAt = lastListing?.checkedAt,
            networks = lastListing?.networks.orEmpty(),
            stale = stale && effective != NetworkVerdict.UNCHECKED
        )
    }

    private fun summarize(
        verdict: NetworkVerdict,
        basis: NetworkCheckRecord,
        latest: NetworkCheckRecord,
        lastListing: NetworkCheckRecord?,
        nowMillis: Long,
        stale: Boolean
    ): String {
        val where = basis.directoryLabel?.trim()?.ifBlank { null }
        val when0 = describeAge(basis.checkedAt, nowMillis)

        val head = when (verdict) {
            NetworkVerdict.IN_NETWORK -> {
                val networks = basis.networks.filter { it.isNotBlank() }
                val inWhat = when {
                    networks.isEmpty() -> "Listed in the directory"
                    networks.size == 1 -> "Listed in ${networks.first()}"
                    else -> "Listed in ${networks.size} networks — ${networks.joinToString(", ")}"
                }
                "$inWhat $when0."
            }
            NetworkVerdict.DROPPED -> {
                val then = lastListing?.let { describeAge(it.checkedAt, nowMillis) } ?: "earlier"
                "Listed $then, and not in the directory checked $when0. " +
                    "That usually means they have left the network — worth ringing the office before booking."
            }
            NetworkVerdict.NEVER_LISTED ->
                "Not in the directory $when0, and no earlier check ever found them."
            NetworkVerdict.AMBIGUOUS ->
                "More than one entry matched that name, and nothing in the directory told them apart. " +
                    "Their NPI would settle it."
            NetworkVerdict.CONFIRMED_BY_PHONE ->
                "Confirmed by phone $when0 — somebody's word, not a published listing."
            NetworkVerdict.DECLINED ->
                "Told by phone $when0 that they are not in network."
            NetworkVerdict.UNAVAILABLE ->
                latest.detail?.trim()?.ifBlank { null }
                    ?: "The plan's directory couldn't be reached, so nothing is known either way."
            NetworkVerdict.UNCHECKED ->
                "Nobody has checked this doctor against the plan's directory yet."
        }

        val tail = listOfNotNull(
            where?.let { "Directory: $it." },
            if (stale && verdict != NetworkVerdict.UNCHECKED) "Worth checking again." else null
        ).joinToString(" ")

        return listOf(head, tail).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun ageDays(then: Long, nowMillis: Long): Long = abs(nowMillis - then) / DAY_MS

    /** "today", "3 days ago", "in March" — how a check's age reads in a sentence. */
    fun describeAge(then: Long, nowMillis: Long): String {
        val days = (nowMillis - then) / DAY_MS
        return when {
            days < 0L -> "just now"
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days < 14L -> "$days days ago"
            days < 60L -> "${days / 7} weeks ago"
            days < 730L -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }
}
