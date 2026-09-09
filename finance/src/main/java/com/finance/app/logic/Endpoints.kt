package com.finance.app.logic

/**
 * The two hosts this app is allowed to talk to, and the arithmetic for turning a provider's dollars
 * into the suite's cents.
 *
 * The allow-list is a *tested function* rather than a convention, for the reason the manifest's
 * promise gives: "Finance talks to Plaid and Mercury and nothing else" is only true if something
 * checks, and the thing that checks has to be cheap enough that every request goes through it. Both
 * network clients call [permits] on the URL they are about to open, and a URL that fails it is a
 * programming error rather than a network one — including, deliberately, a `http://` URL to a host
 * that would otherwise be fine.
 */
object Endpoints {

    /** Plaid's production API. Their sandbox and development hosts are the two below. */
    const val PLAID_PRODUCTION = "https://production.plaid.com"
    const val PLAID_SANDBOX = "https://sandbox.plaid.com"

    /**
     * Mercury's API. Note the `/api/v1` is part of the base rather than of every path, because
     * Mercury versions the whole surface and a v2 would be a one-line change here.
     */
    const val MERCURY_BASE = "https://api.mercury.com/api/v1"

    /**
     * Which Plaid environment a set of credentials belongs to.
     *
     * Kept as a stored choice rather than sniffed from the key, because Plaid's key prefixes have
     * changed before and an app that guessed wrong would send production credentials to the sandbox
     * — which fails safely — or sandbox credentials to production, which fails confusingly.
     */
    enum class PlaidEnvironment(val key: String, val label: String, val baseUrl: String) {
        SANDBOX("sandbox", "Sandbox (test data)", PLAID_SANDBOX),
        PRODUCTION("production", "Production (your real accounts)", PLAID_PRODUCTION);

        companion object {
            fun fromKey(key: String?): PlaidEnvironment =
                entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SANDBOX
        }
    }

    /** Every host this module may open a connection to. Nothing else, ever. */
    private val ALLOWED_HOSTS = setOf(
        "production.plaid.com",
        "sandbox.plaid.com",
        "api.mercury.com",
        // Plaid's Hosted Link, which is the page the person actually logs in to their bank on. It is
        // opened in a browser rather than fetched, but it goes through the same check so that the
        // one place listing where this app can send somebody is this list.
        "link.plaid.com",
        "secure.plaid.com"
    )

    /**
     * Whether [url] is somewhere this app may go.
     *
     * TLS is required rather than preferred: these requests carry an access token that can read a
     * bank account, and there is no such thing as a good reason to send one over plaintext. The host
     * is matched exactly — no suffix matching, because `plaid.com.attacker.net` ends in `plaid.com`
     * and a suffix check is how that becomes a real problem.
     */
    fun permits(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return false
        val afterScheme = trimmed.removePrefix("https://")
        // Userinfo (`https://api.mercury.com@evil.example/`) makes the authority's host the part
        // after the `@`, which is exactly the trick an exact-match check has to survive.
        if (afterScheme.substringBefore('/').contains('@')) return false
        val host = afterScheme.substringBefore('/').substringBefore(':').lowercase()
        return host in ALLOWED_HOSTS
    }

    /**
     * Both providers report money as a decimal number of dollars; the suite stores cents.
     *
     * Rounding rather than truncating, and rounding through a scaled `Math.round` rather than a cast,
     * because `(5.4 * 100).toLong()` is 539 on any IEEE-754 machine and a cent lost on every parse is
     * a balance that never reconciles. This is the single most boring function in the module and the
     * one most likely to be quietly wrong somewhere else.
     */
    fun dollarsToCents(dollars: Double?): Long? {
        if (dollars == null || dollars.isNaN() || dollars.isInfinite()) return null
        return Math.round(dollars * 100.0)
    }
}
