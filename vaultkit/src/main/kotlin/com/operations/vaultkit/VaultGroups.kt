package com.operations.vaultkit

/**
 * One row per *place*, rather than one row per credential.
 *
 * ## The problem this solves, which arrives with the first import
 *
 * A vault somebody typed by hand has one item per site. A vault that has just taken four hundred
 * logins out of a browser has three for the bank — the one they use, the one from the old email
 * address, and the one the browser saved against the mobile site — and a flat list of those is a
 * list nobody can read. The information is not wrong, it is just filed at the wrong level: what the
 * household thinks about is *the bank*, and which sign-in is a question that comes second.
 *
 * So the list groups by site. Everything filed under one address collapses to a single row saying
 * how many sign-ins are behind it, and opening it shows them. A site with one credential is not a
 * group and never looks like one — a folder containing one thing is a tap somebody has to make for
 * no reason.
 *
 * ## Why this is not folders
 *
 * Because nobody maintains it. The grouping is computed from [VaultItem.url] every time the list is
 * drawn, through the same [AutofillMatch.hostOf] that decides what autofill will offer — so a site
 * is one place here exactly when it is one place there, `www.` and paths and ports included. There
 * is nothing to file, nothing to move, nothing to go stale, and nothing to restore. The vault's own
 * note on this is in `ItemsScreen`: a vault that lets you invent categories is a vault where half
 * the logins are under something nobody remembers choosing.
 *
 * An item with no usable address — a secure note, the wifi password, a mirrored credential filed
 * under a [SecretRef] — has no site to be grouped by and stays exactly where it was, as its own
 * row. That is most of what is in a young vault and all of what is in the *From apps* filter, which
 * is why grouping has to be invisible when it has nothing to do.
 */
object VaultGroups {

    /**
     * One line in the list: either a single item, or a site with everything filed under it.
     *
     * The two are one type rather than a sealed pair because every caller wants the same three
     * things from both — a label, a position in the sort, and the items behind it — and the only
     * place the difference matters is whether the row can be opened.
     */
    data class Row(
        /** The host these share, or null for a row that is just an item. */
        val site: String?,
        /** Newest-first is not the order here; see [VaultSearch.defaultOrder]. Never empty. */
        val items: List<VaultItem>
    ) {

        /** More than one credential at one address: the only case that collapses. */
        val isGroup: Boolean get() = site != null && items.size > 1

        /** The item a single row shows, and the first of a group's. */
        val lead: VaultItem get() = items.first()

        /**
         * What the row is called.
         *
         * A group whose items all share a title uses it — three logins all called *Bank* are the
         * bank, and showing `bank.example` instead would be replacing a word somebody chose with
         * one a machine did. Where the titles disagree the address is the honest answer, because it
         * is the only thing they actually have in common.
         */
        val label: String
            get() = if (isGroup) commonTitle ?: site.orEmpty() else lead.title

        /** Shown under a group's label when the label is not already the address. */
        val subtitle: String?
            get() = if (isGroup && commonTitle != null) site else null

        /** A group is a favourite if anything in it is; see the sort note in [group]. */
        val favourite: Boolean get() = items.any { it.favourite }

        val hasPasskey: Boolean get() = items.any { it.hasPasskey }

        private val commonTitle: String?
            get() = items.map { it.title.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .singleOrNull()
    }

    /**
     * Collapse [items] to one row per site, keeping the order the flat list would have had.
     *
     * The sort is the point and it is easy to get wrong. A group takes the position its *best*
     * member would have had — favourite first, then by label — so nothing moves further from the
     * top by being grouped, and a starred login does not disappear into a folder halfway down the
     * alphabet. The result reads as the same list it always was, with some of its rows holding more
     * than one thing.
     */
    fun group(items: List<VaultItem>): List<Row> {
        val live = items.filter { !it.isDeleted }
        val bySite = live.groupBy { AutofillMatch.hostOf(it.url) }

        val rows = ArrayList<Row>(live.size)
        for ((site, group) in bySite) {
            if (site == null || group.size == 1) {
                group.forEach { rows += Row(site = null, items = listOf(it)) }
            } else {
                rows += Row(site = site, items = group.sortedWith(VaultSearch.defaultOrder))
            }
        }

        return rows.sortedWith(
            compareByDescending<Row> { it.favourite }
                .thenBy { it.label.lowercase() }
                .thenBy { it.lead.id }
        )
    }

    /** Every item in [rows], flat and in the order they are drawn — what a count should count. */
    fun flatten(rows: List<Row>): List<VaultItem> = rows.flatMap { it.items }
}
