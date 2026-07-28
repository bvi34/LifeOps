package com.citation.core.oreilly

/**
 * Route O'Reilly through a **library EZproxy** so you reach the licensed content the way you actually
 * have access — a library card, not a personal O'Reilly account. Many public libraries (here,
 * Mid-Continent Public Library) subscribe to O'Reilly Learning and front it with OCLC's EZproxy, which
 * rewrites the origin hostname into a proxy subdomain:
 *
 * ```
 * learning.oreilly.com  →  learning-oreilly-com.mcpl.idm.oclc.org
 * ```
 *
 * The transform is EZproxy's host encoding: each `.` in the origin host becomes `-`, each literal `-`
 * is doubled to `--` (so it round-trips), and the whole thing is prefixed onto the library's proxy
 * host. This class only concerns the **destination host** — parsing a proxied URL back into a
 * [OreillyLink.Destination] already works because that parser is host-agnostic.
 *
 * @property proxyHost the library's EZproxy base host, e.g. `mcpl.idm.oclc.org`.
 */
class OreillyLibraryProxy(val proxyHost: String) {

    init {
        require(proxyHost.isNotBlank()) { "proxy host must not be blank" }
    }

    /** The proxied host for an origin [originHost] (e.g. `learning.oreilly.com`). */
    fun proxiedHost(originHost: String): String = encodeHost(originHost) + "." + proxyHost

    /** True if [url]'s host is under this proxy (ends with `.<proxyHost>`). */
    fun isProxied(url: String): Boolean {
        val host = hostOf(url)?.lowercase() ?: return false
        return host == proxyHost.lowercase() || host.endsWith("." + proxyHost.lowercase())
    }

    /**
     * Rewrite a direct O'Reilly [url] to go through this proxy. If it's already proxied (or not an
     * `http(s)` URL we can parse), it's returned unchanged — the operation is idempotent, so building a
     * deep link twice never double-encodes.
     */
    fun rewrite(url: String): String {
        if (isProxied(url)) return url
        val m = AUTHORITY.find(url) ?: return url
        val scheme = m.groupValues[1]
        val host = m.groupValues[2]
        val rest = url.substring(m.range.last + 1)
        return scheme + proxiedHost(host) + rest
    }

    /** Undo [rewrite]: a proxied [url] back to its direct origin. Non-proxied URLs pass through. */
    fun directUrl(url: String): String {
        if (!isProxied(url)) return url
        val m = AUTHORITY.find(url) ?: return url
        val scheme = m.groupValues[1]
        val proxied = m.groupValues[2]
        val encodedOrigin = proxied.removeSuffix("." + proxyHost).removeSuffix(proxyHost).trimEnd('.')
        val rest = url.substring(m.range.last + 1)
        return scheme + decodeHost(encodedOrigin) + rest
    }

    companion object {
        /** `https://` or `http://` capturing the scheme and the authority (host, no path). */
        private val AUTHORITY = Regex("^(https?://)([^/?#]+)")

        /** Mid-Continent Public Library's OCLC EZproxy — the default here. */
        const val MID_CONTINENT_HOST = "mcpl.idm.oclc.org"

        /** A ready-made proxy for Mid-Continent Public Library. */
        val MID_CONTINENT = OreillyLibraryProxy(MID_CONTINENT_HOST)

        /** EZproxy host encode: `-` → `--`, then `.` → `-`. Order matters so dashes round-trip. */
        fun encodeHost(host: String): String = host.replace("-", "--").replace(".", "-")

        /** Inverse of [encodeHost]: `--` → `-`, a lone `-` → `.`. */
        fun decodeHost(encoded: String): String {
            val out = StringBuilder(encoded.length)
            var i = 0
            while (i < encoded.length) {
                val c = encoded[i]
                if (c == '-') {
                    if (i + 1 < encoded.length && encoded[i + 1] == '-') {
                        out.append('-'); i += 2
                    } else {
                        out.append('.'); i += 1
                    }
                } else {
                    out.append(c); i += 1
                }
            }
            return out.toString()
        }

        private fun hostOf(url: String): String? = AUTHORITY.find(url)?.groupValues?.get(2)
    }
}
