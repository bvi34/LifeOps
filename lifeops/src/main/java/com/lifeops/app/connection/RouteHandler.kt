package com.lifeops.app.connection

/** A parsed address plus its payload — everything a handler needs to serve one call. */
data class ConnectionRequest(
    val address: ConnectionAddress,
    val params: ConnectionParams = ConnectionParams.EMPTY
)

/**
 * Serves a single resource/action route. Implementations are thin adapters: validate the payload
 * and delegate to the service layer. They may throw [MissingParamException] (→ INVALID_PARAMS) or
 * any other exception (→ HANDLER_ERROR); the dispatcher wraps both.
 */
fun interface RouteHandler {
    suspend fun handle(request: ConnectionRequest): ConnectionResult
}
