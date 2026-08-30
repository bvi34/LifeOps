package com.maintenance.app.logic

import kotlin.math.roundToLong

/**
 * The paperwork on a thing that expires: insurance, a warranty, a registration, an inspection, a
 * service contract.
 *
 * These sit next to upkeep plans rather than inside them because they are a different kind of
 * obligation — nobody *does* a registration the way they change an oil filter; it renews, and
 * missing it costs a fine rather than a part. But they land on the same due list, because the
 * question "what does this house need from me in the next fortnight" does not care which of the two
 * an answer came from.
 */
enum class CoverageKind(val key: String, val label: String) {
    INSURANCE("insurance", "Insurance"),
    WARRANTY("warranty", "Warranty"),
    REGISTRATION("registration", "Registration"),
    INSPECTION("inspection", "Inspection"),
    SERVICE_CONTRACT("service_contract", "Service contract");

    companion object {
        fun of(key: String?): CoverageKind = entries.firstOrNull { it.key == key } ?: WARRANTY
    }
}

/** How often the premium is paid, and what that is worth over a year. */
enum class PremiumPeriod(val key: String, val label: String, val perYear: Double) {
    MONTHLY("monthly", "a month", 12.0),
    QUARTERLY("quarterly", "a quarter", 4.0),
    SEMIANNUAL("semiannual", "every 6 months", 2.0),
    ANNUAL("annual", "a year", 1.0),
    /** Paid once — a warranty bought with the appliance. Nothing recurs, so nothing annualises. */
    ONE_TIME("one_time", "one-off", 0.0);

    companion object {
        fun of(key: String?): PremiumPeriod = entries.firstOrNull { it.key == key } ?: ANNUAL
    }
}

/** One policy, warranty or registration on an asset. */
data class Coverage(
    val id: String,
    val assetId: String,
    val kind: CoverageKind,
    val provider: String,
    val policyNumber: String? = null,
    val premiumCents: Long = 0L,
    val period: PremiumPeriod = PremiumPeriod.ANNUAL,
    val startsAt: Long? = null,
    /** When it lapses or renews. Null is a policy with no end date — rare, and allowed. */
    val expiresAt: Long? = null,
    val notes: String? = null
)

object Coverages {

    /** Renewals get more warning than services: a month, because they take paperwork. */
    const val SOON_DAYS = 30

    /**
     * How a coverage's expiry reads today.
     *
     * A coverage with no expiry is [DueStatus.DORMANT] rather than fine-forever: it is on file, it
     * is not a date, and the due list should not carry it.
     */
    fun status(coverage: Coverage, now: Long, soonDays: Int = SOON_DAYS): DueStatus {
        val expires = coverage.expiresAt ?: return DueStatus.DORMANT
        val remaining = expires - now
        return when {
            remaining <= 0L -> DueStatus.OVERDUE
            remaining <= soonDays.toLong() * Upkeep.DAY_MILLIS -> DueStatus.DUE_SOON
            else -> DueStatus.SCHEDULED
        }
    }

    /**
     * "Lapsed 3 days ago" / "Renews in 3 weeks" — the line a due row shows.
     *
     * A warranty *expires* and is gone; a policy or a plate *lapses* and wants renewing. Same
     * arithmetic, two different things to do about it, so they are not given the same sentence.
     */
    fun summary(coverage: Coverage, now: Long): String {
        val expires = coverage.expiresAt ?: return "No end date"
        val days = Math.ceil((expires - now).toDouble() / Upkeep.DAY_MILLIS).toInt()
        val expiring = coverage.kind == CoverageKind.WARRANTY
        return when {
            days < 0 -> if (expiring) "Expired ${plainDays(-days)} ago" else "Lapsed ${plainDays(-days)} ago"
            days == 0 -> if (expiring) "Expires today" else "Renews today"
            else -> if (expiring) "Expires in ${plainDays(days)}" else "Renews in ${plainDays(days)}"
        }
    }

    /** What this coverage costs over a year; one-off premiums annualise to nothing. */
    fun annualCents(coverage: Coverage): Long =
        (coverage.premiumCents * coverage.period.perYear).roundToLong()

    /** The annual cost of every coverage on an asset — the standing half of what it costs to own. */
    fun annualCents(coverages: List<Coverage>): Long = coverages.sumOf { annualCents(it) }

    private fun plainDays(count: Int): String = when {
        count == 1 -> "1 day"
        count < 14 -> "$count days"
        count < 60 -> "${count / 7} weeks"
        else -> "${count / 30} months"
    }
}
