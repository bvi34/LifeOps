package com.operations.vaultkit

/**
 * The address one app's secret is known by: `finance/plaid/access-token`.
 *
 * Three segments, and the shape is deliberately the same idea as the suite's connection addresses
 * (`/v1/{application}/{connection}/{resource}/{action}`) without being them — :vaultkit does not
 * depend on :connectkit and must not, because a password manager that can be *called* is a password
 * manager with an attack surface. Nothing dispatches on a [SecretRef]; it is a filing name.
 *
 *  - [app] — who owns it. The [com.operations.backupkit.AppId] key, so an item can be shown as
 *    "managed by Finance" without a second table saying which app that is.
 *  - [connection] — which of that app's several things it belongs to: a Plaid connection's id, a
 *    catalogue's id, `self` for an app-wide secret with nothing to hang it off.
 *  - [name] — which secret of that connection: `access-token`, `client-secret`, `password`.
 *
 * Segments are lower-case, and may hold letters, digits, `-`, `_` and `.`. Slashes are the one thing
 * that cannot appear, because a name holding one would parse back as something else — [parse]
 * refuses rather than guessing which of the four segments was meant.
 */
data class SecretRef(val app: String, val connection: String, val name: String) {

    init {
        require(valid(app) && valid(connection) && valid(name)) {
            "secret ref segments must be lower-case [a-z0-9._-] and non-empty"
        }
    }

    fun format(): String = "$app/$connection/$name"

    override fun toString(): String = format()

    companion object {

        /** The connection segment for a secret that belongs to the app itself, not to one of its rows. */
        const val SELF = "self"

        private val SEGMENT = Regex("[a-z0-9._-]+")

        fun valid(segment: String): Boolean = segment.isNotEmpty() && SEGMENT.matches(segment)

        /** Parse `app/connection/name`, or null if it is not one. */
        fun parse(address: String): SecretRef? {
            val parts = address.trim().split('/')
            if (parts.size != 3) return null
            if (!parts.all { valid(it) }) return null
            return SecretRef(parts[0], parts[1], parts[2])
        }

        /**
         * Make a segment out of something that was not written to be one — a connection id, an
         * account name, a catalogue's row key.
         *
         * Lower-cased, with everything outside the alphabet turned into `-`. Two different inputs
         * *can* land on the same segment (`My Bank` and `my/bank` both become `my-bank`), which
         * matters: callers pass row ids, which are already unique and already in the alphabet, and
         * anything else is a caller choosing a filing name where a collision is theirs to notice.
         */
        fun segment(raw: String): String {
            // Explicitly ASCII rather than `isLetterOrDigit()`, which is true of `é` and `٧` and
            // would hand back a "cleaned" segment that [valid] then rejects.
            val cleaned = raw.trim().lowercase().map { ch ->
                if (ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') ch else '-'
            }.joinToString("").trim('-')
            return cleaned.ifEmpty { "unnamed" }
        }
    }
}
