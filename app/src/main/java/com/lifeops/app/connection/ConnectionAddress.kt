package com.lifeops.app.connection

/**
 * A parsed connection-function address of the form
 * `/v1/{application}/{connection}/{resource}/{action}` — for example
 * `/v1/LifeOps/local/task/create`.
 *
 * The addressing scheme is a logical, in-process routing convention (this app is offline and
 * single-user; there is no HTTP server behind it). It gives every callable action one stable,
 * greppable name regardless of who invokes it:
 *
 *  - **version** — the address contract version (`v1`). Bumped only on a breaking shape change.
 *  - **application** — the owning app (`LifeOps`). Reserved so a future multi-app surface can
 *    address peers without ambiguity.
 *  - **connection** — the transport/namespace. `local` is internal app comms; named connections
 *    (an API or integration name) are reserved for future external integrations.
 *  - **resource** — the noun being acted on (`task`, `week`, …).
 *  - **action** — the verb (`create`, `update`, `complete`, `delete`, …).
 */
data class ConnectionAddress(
    val version: String,
    val application: String,
    val connection: String,
    val resource: String,
    val action: String
) {
    /** Registry key for the handler that serves this address (version/application agnostic). */
    val routeKey: String get() = "$connection/$resource/$action"

    /** Canonical `/v1/App/connection/resource/action` string form. */
    override fun toString(): String = "/$version/$application/$connection/$resource/$action"

    companion object {
        const val CURRENT_VERSION = "v1"
        const val APPLICATION = "LifeOps"

        const val CONNECTION_LOCAL = "local"

        private const val SEGMENT_COUNT = 5

        /**
         * Structurally parse [raw] into its five segments. Leading/trailing slashes and surrounding
         * whitespace are tolerated. Returns `null` when the shape is wrong (not exactly five
         * non-blank segments) — semantic validation (supported version, known connection, …) is the
         * dispatcher's job so it can report a specific error.
         */
        fun parse(raw: String): ConnectionAddress? {
            val segments = raw.trim().trim('/').split('/')
            if (segments.size != SEGMENT_COUNT) return null
            if (segments.any { it.isBlank() }) return null
            val (version, application, connection, resource, action) = segments
            return ConnectionAddress(
                version = version,
                application = application,
                connection = connection,
                resource = resource,
                action = action
            )
        }

        /** Build a canonical [ConnectionAddress] for the current version + application. */
        fun local(resource: String, action: String): ConnectionAddress =
            ConnectionAddress(CURRENT_VERSION, APPLICATION, CONNECTION_LOCAL, resource, action)
    }
}
