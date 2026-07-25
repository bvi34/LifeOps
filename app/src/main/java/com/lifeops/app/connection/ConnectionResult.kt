package com.lifeops.app.connection

/** Outcome of dispatching a connection request. */
sealed interface ConnectionResult {

    /** The handler ran and succeeded. [data] carries any values it chose to return. */
    data class Success(val data: Map<String, Any?> = emptyMap()) : ConnectionResult {
        operator fun get(key: String): Any? = data[key]
    }

    /** The request could not be served. [error] is the machine-readable reason. */
    data class Failure(val error: ConnectionError, val message: String) : ConnectionResult

    val isSuccess: Boolean get() = this is Success

    companion object {
        fun ok(vararg data: Pair<String, Any?>): Success = Success(data.toMap())

        fun fail(error: ConnectionError, message: String): Failure = Failure(error, message)
    }
}

/** Machine-readable failure reasons, ordered roughly from routing to execution. */
enum class ConnectionError {
    /** The address string was not five non-blank `/`-separated segments. */
    MALFORMED_ADDRESS,

    /** The address version is not one this dispatcher understands. */
    UNSUPPORTED_VERSION,

    /** The address named an application this dispatcher does not serve. */
    UNKNOWN_APPLICATION,

    /** No such connection namespace is registered (e.g. an integration not yet wired). */
    UNKNOWN_CONNECTION,

    /** The connection exists but has no handler for this resource/action pair. */
    ROUTE_NOT_FOUND,

    /** The route ran but the addressed entity does not exist (e.g. no task with that id). */
    NOT_FOUND,

    /** The handler rejected the payload (missing/invalid parameters). */
    INVALID_PARAMS,

    /** The handler threw while executing. */
    HANDLER_ERROR
}
