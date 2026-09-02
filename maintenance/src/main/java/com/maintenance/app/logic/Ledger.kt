package com.maintenance.app.logic

import kotlin.math.roundToLong

/**
 * One asset, as the ledger needs to see it.
 *
 * Deliberately not `data/model/Asset`: `logic/` cannot see the store's types, and the ledger needs
 * only what it counts by. [owedCents] arrives already worked out, because loan arithmetic is
 * `logic/Loan`'s job and doing it twice is how two screens end up disagreeing about a balance.
 */
data class LedgerAsset(
    val id: String,
    val name: String,
    val kind: AssetKind,
    val worthCents: Long? = null,
    val owedCents: Long = 0L,
    val archived: Boolean = false,
    /** Carried only so the costs list draws the same mark the register does. */
    val colorArgb: Long = 0L
)

/** What one asset cost over the window, and where it stands today. */
data class AssetSpend(
    val assetId: String,
    val name: String,
    val kind: AssetKind,
    val colorArgb: Long,
    val archived: Boolean,
    /** Work logged in the window. */
    val serviceCents: Long,
    /** Premiums for the same window: annualised, then pro-rated to its length. */
    val coverageCents: Long,
    val entries: Int
) {
    val allInCents: Long get() = serviceCents + coverageCents
}

/**
 * One vendor and what they have had from this household.
 *
 * [name] is the spelling they were written under most recently, because that is the one somebody
 * has just looked at the invoice and typed.
 */
data class VendorSpend(
    val name: String,
    val visits: Int,
    val totalCents: Long,
    val lastAt: Long,
    /** How many of your things they have touched — the reason this is worth crossing assets for. */
    val assets: Int
)

/**
 * What the whole register costs, owes and is worth.
 *
 * Assembled on read like everything else here, so it cannot be stale and there is nothing for a
 * restore to leave inconsistent.
 */
data class Ledger(
    /** The start of the window, or null when it is everything. */
    val since: Long?,
    /** Every asset that cost something, most expensive first. */
    val spend: List<AssetSpend>,
    /** Every vendor paid in the window, most paid first. */
    val vendors: List<VendorSpend>,
    val serviceCents: Long,
    val coverageCents: Long,
    /** What is still owed today, across the things you still own. */
    val owedCents: Long,
    /** What you last said those things were worth. */
    val worthCents: Long,
    /** How many of them have a figure at all — the rest make [worthCents] an understatement. */
    val valued: Int,
    val assets: Int
) {
    val allInCents: Long get() = serviceCents + coverageCents

    val isEmpty: Boolean get() = allInCents == 0L && owedCents == 0L && worthCents == 0L

    /**
     * Worth minus owed, or null when nothing has been valued.
     *
     * Null rather than a bare negative: with no values typed in, "equity" would be the loan balance
     * with a minus sign on it, which reads as a fact and is an artefact of an empty field.
     */
    val equityCents: Long? get() = if (valued == 0) null else worthCents - owedCents
}

/**
 * The household's money, across everything you own.
 *
 * Per-asset costs already existed — this is the same arithmetic asked sideways, which is the only
 * way to answer the two questions an asset page structurally cannot: *what is all of this costing
 * me?* and *which of these things is eating the money?*
 *
 * One decision worth naming. **Costs include the things you have sold; worth and owed do not.**
 * Spend is history, and history includes the truck you had until March — leaving it out would make
 * a year look cheaper than it was, which is the one thing a costs screen must never do. What
 * something is worth and what is owed on it are claims about *now*, and you do not own it now.
 */
object Ledgers {

    private const val YEAR_MILLIS = 365L * Upkeep.DAY_MILLIS

    /** The window the screen opens on: the last twelve months, ending now. */
    fun yearTo(now: Long): Long = now - YEAR_MILLIS

    fun of(
        assets: List<LedgerAsset>,
        entries: List<ServiceEntry>,
        coverages: Map<String, List<Coverage>>,
        since: Long?,
        now: Long
    ): Ledger {
        val inWindow = if (since == null) entries else entries.filter { it.performedAt >= since }
        val byAsset = inWindow.groupBy { it.assetId }

        // Premiums are a standing cost rather than an event, so they are annualised and then
        // pro-rated to the window — the same rule `Costs.summary` applies to one asset, applied to
        // all of them, so a total here and a total there cannot disagree.
        val span = if (since == null) null else (now - since).coerceAtLeast(0L)

        val spend = assets.map { asset ->
            val own = byAsset[asset.id].orEmpty()
            val annual = Coverages.annualCents(coverages[asset.id].orEmpty())
            AssetSpend(
                assetId = asset.id,
                name = asset.name,
                kind = asset.kind,
                colorArgb = asset.colorArgb,
                archived = asset.archived,
                serviceCents = own.sumOf { it.costCents },
                // With no window there is nothing to pro-rate a standing cost against, so an
                // all-time figure counts one year of it — the honest reading of "a year's premiums"
                // when the question was "what does this cost".
                coverageCents = if (span == null) annual else (annual * (span.toDouble() / YEAR_MILLIS)).roundToLong(),
                entries = own.size
            )
        }.filter { it.allInCents != 0L || it.entries > 0 }
            .sortedWith(compareByDescending<AssetSpend> { it.allInCents }.thenBy { it.name.lowercase() })

        val current = assets.filterNot { it.archived }

        return Ledger(
            since = since,
            spend = spend,
            vendors = Vendors.directory(inWindow),
            serviceCents = spend.sumOf { it.serviceCents },
            coverageCents = spend.sumOf { it.coverageCents },
            owedCents = current.sumOf { it.owedCents },
            worthCents = current.sumOf { it.worthCents ?: 0L },
            valued = current.count { it.worthCents != null },
            assets = current.size
        )
    }
}

/**
 * Who has been paid, out of a column of free text.
 *
 * The vendor on a service record is a name somebody typed, and the same garage gets typed three
 * ways over five years — "Quick Lube", "quick lube", "Quick  Lube ". This is the whole of what is
 * done about that, and it is deliberately not a directory of vendor *records*: a household has no
 * appetite for maintaining one, and the moment a name has to be picked from a list rather than
 * typed, the field stops getting filled in at all.
 *
 * So the names are folded together for counting and offered back as suggestions while you type,
 * which fixes the spelling at the point it is being introduced rather than afterwards.
 */
object Vendors {

    /** Trimmed, with runs of whitespace collapsed. Null when there was nothing there. */
    fun normalise(raw: String?): String? =
        raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

    /** What two spellings of one garage have in common. */
    fun key(name: String): String = normalise(name)?.lowercase().orEmpty()

    /**
     * Everyone paid, most paid first.
     *
     * Grouped case-insensitively and shown under the spelling used most recently: the newest one is
     * the one somebody typed with the invoice in front of them.
     */
    fun directory(entries: List<ServiceEntry>): List<VendorSpend> =
        entries.mapNotNull { entry -> normalise(entry.vendor)?.let { it to entry } }
            .groupBy { (name, _) -> name.lowercase() }
            .map { (_, group) ->
                val newest = group.maxBy { (_, entry) -> entry.performedAt }
                VendorSpend(
                    name = newest.first,
                    visits = group.size,
                    totalCents = group.sumOf { (_, entry) -> entry.costCents },
                    lastAt = newest.second.performedAt,
                    assets = group.map { (_, entry) -> entry.assetId }.distinct().size
                )
            }
            .sortedWith(compareByDescending<VendorSpend> { it.totalCents }.thenByDescending { it.lastAt })

    /**
     * Names to offer under the vendor field, most recently used first.
     *
     * Matching is on *containing* what has been typed rather than starting with it, because
     * "Quick Lube on 5th" is remembered as "5th" as often as by its name. An exact match is not
     * offered back — there is nothing to complete — and nothing is offered for a blank field beyond
     * the handful used last, which is the case where a suggestion saves the most typing.
     */
    fun suggestions(entries: List<ServiceEntry>, typed: String, limit: Int = 3): List<String> {
        val query = normalise(typed)?.lowercase()
        return directory(entries)
            .sortedByDescending { it.lastAt }
            .map { it.name }
            .filter { name ->
                query == null || (name.lowercase().contains(query) && !name.equals(query, ignoreCase = true))
            }
            .take(limit)
    }

    private val WHITESPACE = Regex("\\s+")
}
