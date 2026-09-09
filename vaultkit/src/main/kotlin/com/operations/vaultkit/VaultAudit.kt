package com.operations.vaultkit

/**
 * The honest mirror, for passwords.
 *
 * LifeOps keeps receipts on how a week actually went; this is the same idea pointed at a vault. It
 * reads what is in there and says the three things a password manager is uniquely able to say and
 * that nobody can work out for themselves:
 *
 *  - **this one is weak** — short, one character class, or the same character eleven times;
 *  - **this one is used somewhere else** — the finding no strength meter can produce, because it is
 *    a property of the collection rather than of the password;
 *  - **this one has not changed in years** — reported as a fact, not a scolding. Age is not a
 *    vulnerability on its own; NIST stopped recommending routine rotation years ago. What makes it
 *    worth showing is the pairing: a password that is five years old *and* used on three sites is a
 *    password from before you had this app.
 *
 * Nothing here phones anybody. There is no breach check, no "have I been pwned" lookup, no hash
 * prefix sent anywhere — that would mean this module made network calls, and the promise in the
 * manifest is worth more than the feature.
 */
object VaultAudit {

    enum class Kind { WEAK, REUSED, STALE, NO_SECRET }

    data class Finding(
        val itemId: String,
        val title: String,
        val kind: Kind,
        /** For [Kind.REUSED], the other items sharing this secret. Empty otherwise. */
        val alsoUsedBy: List<String> = emptyList(),
        val bits: Double = 0.0
    )

    /** What the audit screen shows at the top: one number per kind, plus the total looked at. */
    data class Report(
        val findings: List<Finding>,
        val examined: Int
    ) {
        fun count(kind: Kind): Int = findings.count { it.kind == kind }
        val clean: Boolean get() = findings.isEmpty()
    }

    /**
     * Audit [items].
     *
     * [staleAfterMillis] defaults to two years. Managed secrets — the ones an app mirrored here —
     * are examined for reuse and weakness like anything else but are **never reported as stale**: a
     * Plaid access token is not something a person can go and change, and an audit that nags about
     * things its reader cannot act on is an audit its reader learns to ignore.
     */
    fun run(
        items: List<VaultItem>,
        now: Long,
        staleAfterMillis: Long = TWO_YEARS
    ): Report {
        val live = items.filter { !it.isDeleted }

        // Reuse is computed over the raw secret, and this is the one place in the module where
        // secrets are compared to each other. It happens in memory, on an already-unlocked vault,
        // and nothing derived from it is stored: the grouping key is the secret itself rather than a
        // hash of it precisely because a hash here would look like it was protecting something while
        // sitting in the same heap as the plaintext it was made from.
        val bySecret = live.filter { it.secret.isNotEmpty() }.groupBy { it.secret }

        val findings = ArrayList<Finding>()

        for (item in live) {
            if (item.secret.isEmpty()) {
                // A login with no password is a stub somebody started and abandoned; a note is
                // allowed to have nothing in the secret field, because for a note the contents *are*
                // the note.
                if (item.kind != VaultItemKind.NOTE) {
                    findings += Finding(item.id, item.title, Kind.NO_SECRET)
                }
                continue
            }

            val bits = SecretStrength.bits(item.secret)
            if (SecretStrength.rate(bits) == SecretStrength.Rating.WEAK) {
                findings += Finding(item.id, item.title, Kind.WEAK, bits = bits)
            }

            val sharing = bySecret[item.secret].orEmpty().filter { it.id != item.id }
            if (sharing.isNotEmpty()) {
                findings += Finding(
                    itemId = item.id,
                    title = item.title,
                    kind = Kind.REUSED,
                    alsoUsedBy = sharing.map { it.title },
                    bits = bits
                )
            }

            if (!item.isManaged && item.updatedAt > 0 && now - item.updatedAt > staleAfterMillis) {
                findings += Finding(item.id, item.title, Kind.STALE, bits = bits)
            }
        }

        return Report(findings = findings, examined = live.size)
    }

    val TWO_YEARS = 730L * 24 * 60 * 60 * 1000
}

/**
 * Finding things in a vault.
 *
 * One rule, and it is the whole reason this is a named object rather than a `filter` at the call
 * site: **the search never looks at a secret.** Not at [VaultItem.secret], not at a field marked
 * secret. Typing a password into a search box to see where you used it is a reasonable thing to
 * want and a terrible thing to support — it puts the password in a text field, in an input method's
 * learned-words store, and in whatever the keyboard app does with what it sees. The audit already
 * answers that question ([VaultAudit.Kind.REUSED]) without anybody typing anything.
 */
object VaultSearch {

    /**
     * Items matching [query], best first.
     *
     * Ranked, because a vault with three hundred items and a search for "bank" should put the item
     * called Bank above the one whose note mentions it. Title matches beat everything, a match at
     * the start of the title beats one in the middle, and the rest fall back to alphabetical so the
     * list is stable while somebody types.
     */
    fun search(items: List<VaultItem>, query: String): List<VaultItem> {
        val q = query.trim().lowercase()
        val live = items.filter { !it.isDeleted }
        if (q.isEmpty()) return live.sortedWith(defaultOrder)
        return live.filter { it.matches(q) }
            .sortedWith(compareByDescending<VaultItem> { score(it, q) }.then(defaultOrder))
    }

    private fun score(item: VaultItem, query: String): Int {
        val title = item.title.lowercase()
        return when {
            title == query -> 100
            title.startsWith(query) -> 80
            title.contains(query) -> 60
            item.username.lowercase().startsWith(query) -> 50
            item.url.lowercase().contains(query) -> 40
            item.tags.any { it.lowercase() == query } -> 35
            item.ref?.lowercase()?.contains(query) == true -> 30
            else -> 10
        }
    }

    /** Favourites first, then by title. What the list looks like when nobody has typed anything. */
    val defaultOrder: Comparator<VaultItem> =
        compareByDescending<VaultItem> { it.favourite }.thenBy { it.title.lowercase() }.thenBy { it.id }
}
