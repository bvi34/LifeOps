package com.operations.vaultkit

/**
 * Which items, if any, may be offered to the thing asking for a password.
 *
 * ## Why this is the dangerous half of autofill, and why it is here
 *
 * An autofill service is handed a request by an app or a web page and answers with credentials. The
 * failure mode is not subtle: answer the wrong asker and the vault has just typed the bank password
 * into whatever was pretending to be the bank. Everything else about autofill is plumbing — reading
 * a view tree, filling in a `Dataset` — and this is the part with a decision in it.
 *
 * So it lives in `:vaultkit`, framework-free and unit-tested, for exactly the reason the crypto
 * does: a rule that can only be exercised on a device is a rule nobody exercises. The tests beside
 * this file are mostly about what it *refuses*.
 *
 * ## The rules
 *
 * 1. **A managed credential is never offered.** Finance's access token is not a login, no sign-in
 *    page wants it, and the only thing typing it into one could achieve is handing a bank token to
 *    a form. Mirrored items are filtered out before anything else is considered.
 * 2. **A match must be earned.** The asker names itself — a web domain for a browser, a package
 *    name for an app — and an item is only a candidate if its own address says it belongs there.
 *    Nothing is offered on a guess about the title.
 * 3. **A subdomain matches its parent, and nothing else does.** `login.bank.com` may be filled from
 *    an item filed under `bank.com`, because that is one site. `bank.com.evil.example` may not,
 *    because the suffix test is on label boundaries rather than on characters — which is the whole
 *    difference between the two.
 * 4. **No match means no offer.** Not "show the vault and let them pick" from inside the dropdown:
 *    a service that offers everything to everybody has given up on rule 2. The app has its own
 *    list, reachable in one tap, and a person choosing an item themselves is a different and much
 *    better-founded act than a service deciding on their behalf.
 */
object AutofillMatch {

    /** How well an item fits the asker. Higher is better; the order is the reason this is an enum. */
    enum class Strength { EXACT_DOMAIN, PARENT_DOMAIN, PACKAGE }

    data class Candidate(val item: VaultItem, val strength: Strength)

    /**
     * The items that may be offered to [webDomain] (a browser's page) or [packageName] (an app),
     * best first.
     *
     * [webDomain] wins when both are present, which is the browser case: Chrome asking on behalf of
     * `bank.com` names itself as Chrome's package *and* the page's domain, and the page is what the
     * credential belongs to. Filling a browser from an item filed under `com.android.chrome` would
     * be filling every site from the same row.
     */
    fun candidates(
        items: List<VaultItem>,
        packageName: String? = null,
        webDomain: String? = null
    ): List<Candidate> {
        val host = hostOf(webDomain)
        val package_ = packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (host == null && package_ == null) return emptyList()

        return items.asSequence()
            .filter { !it.isDeleted }
            // Rule 1, first and unconditionally.
            .filter { !it.isManaged }
            // An item with neither a username nor a password fills nothing; offering it would be a
            // row in the dropdown that does nothing when tapped.
            .filter { it.secret.isNotEmpty() || it.username.isNotEmpty() }
            .mapNotNull { item ->
                strengthFor(item, host, package_)?.let { Candidate(item, it) }
            }
            .sortedWith(
                compareBy<Candidate> { it.strength.ordinal }
                    .thenByDescending { it.item.favourite }
                    .thenBy { it.item.title.lowercase() }
                    .thenBy { it.item.id }
            )
            .toList()
    }

    private fun strengthFor(item: VaultItem, host: String?, packageName: String?): Strength? {
        val itemHost = hostOf(item.url)

        if (host != null && itemHost != null) {
            when {
                itemHost == host -> return Strength.EXACT_DOMAIN
                // Either direction: an item filed under the bare domain fills its login subdomain,
                // and an item filed under the login subdomain fills the bare domain. Both are one
                // site; the label-boundary test below is what keeps a lookalike out of it.
                isSubdomainOf(host, itemHost) || isSubdomainOf(itemHost, host) ->
                    return Strength.PARENT_DOMAIN
                else -> return null
            }
        }

        // No page domain: an app asking for itself. The only honest link between `com.monzo.app`
        // and an item filed under `monzo.com` is the convention that a package name is a domain
        // written backwards, so that is what is checked — and it is ranked below a real domain
        // match because it is a convention rather than a statement.
        if (packageName != null && itemHost != null && matchesPackage(itemHost, packageName)) {
            return Strength.PACKAGE
        }

        return null
    }

    /**
     * The host part of [value], whether it arrives as a bare domain or a whole URL.
     *
     * `www.` is dropped because `www.bank.com` and `bank.com` are one site that nobody thinks of as
     * two, and an item saved from a browser's address bar has the prefix roughly half the time.
     */
    fun hostOf(value: String?): String? {
        var text = value?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        val scheme = text.indexOf("://")
        if (scheme >= 0) text = text.substring(scheme + 3)
        text = text.substringBefore('/').substringBefore('?').substringBefore('#')
        // Credentials in a URL (`user@host`) and an explicit port are both part of an address and
        // neither is part of the host.
        text = text.substringAfterLast('@').substringBefore(':')
        if (text.startsWith("www.")) text = text.removePrefix("www.")

        // A host has at least one dot and no spaces. Anything else is somebody's note about where
        // they use this password, and a note is not an address.
        if (text.isEmpty() || ' ' in text || '.' !in text) return null
        return text.trim('.').takeIf { it.isNotEmpty() }
    }

    /**
     * Is [candidate] a subdomain of [parent]?
     *
     * The test is on a label boundary — `.` plus the parent — rather than on the string ending,
     * which is the entire security content of this function. `bank.com.evil.example` ends with
     * `evil.example` and does not end with `.bank.com`, so it is not a subdomain of `bank.com` and
     * never gets that password.
     *
     * It deliberately does not consult a public suffix list, which means `a.co.uk` and `b.co.uk`
     * both count as subdomains of `co.uk` — a household would have to have filed an item under the
     * literal host `co.uk` for that to matter, and inventing a suffix list that ships stale is a
     * worse answer than the one case it would fix.
     */
    fun isSubdomainOf(candidate: String, parent: String): Boolean =
        candidate != parent && candidate.endsWith(".$parent")

    /**
     * Does [packageName] look like [host] written backwards? `com.monzo.app` ↔ `monzo.com`.
     *
     * Compared label by label rather than as a string: `com.monzo` is a prefix of `com.monzo.app`
     * by labels, while `com.monzonian` is not, even though one string starts with the other.
     */
    fun matchesPackage(host: String, packageName: String): Boolean {
        val reversed = host.split('.').filter { it.isNotEmpty() }.reversed()
        if (reversed.isEmpty()) return false
        val labels = packageName.split('.').filter { it.isNotEmpty() }
        if (labels.size < reversed.size) return false
        return labels.subList(0, reversed.size) == reversed
    }
}
