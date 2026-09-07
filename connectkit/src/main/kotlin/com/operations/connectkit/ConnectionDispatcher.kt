package com.operations.connectkit

/**
 * The single entry point for connection-function calls. Parses an address string, validates it
 * against the supported version/application, looks up the handler, and invokes it — converting any
 * failure into a typed [ConnectionResult.Failure] so callers never have to catch exceptions.
 *
 *     val result = dispatcher.dispatch(
 *         "/v1/LifeOps/local/task/create",
 *         ConnectionParams.of("title" to "Ship the thing", "priority" to "high")
 *     )
 *
 * The core stays pure JVM (no Android dependency) so it is trivially unit-testable; unexpected
 * handler exceptions are reported through [onHandlerError], which the composition root wires to
 * `android.util.Log`.
 */
class ConnectionDispatcher(
    private val registry: ConnectionRegistry,
    /**
     * The application segment this dispatcher answers for — `LifeOps`, `Project`, `Repository`.
     *
     * Required, and deliberately without a default. It defaulted to LifeOps while LifeOps was the
     * only app serving addresses, which is exactly the kind of default that stops being true
     * quietly: a third app that forgot to pass its own segment would have built a dispatcher
     * answering for somebody else's, and found out when a route it registered returned
     * UNKNOWN_APPLICATION for its own address.
     *
     * Declared before [onHandlerError] on purpose: that one is passed as a trailing lambda at every
     * call site, and a parameter added after it would silently capture the lambda instead.
     */
    private val application: String,
    private val onHandlerError: (address: ConnectionAddress, error: Throwable) -> Unit = { _, _ -> }
) {

    suspend fun dispatch(rawAddress: String, params: ConnectionParams = ConnectionParams.EMPTY): ConnectionResult {
        val address = ConnectionAddress.parse(rawAddress)
            ?: return ConnectionResult.fail(
                ConnectionError.MALFORMED_ADDRESS,
                "Address must be /v1/{application}/{connection}/{resource}/{action}: '$rawAddress'"
            )
        return dispatch(ConnectionRequest(address, params))
    }

    suspend fun dispatch(request: ConnectionRequest): ConnectionResult {
        val address = request.address

        if (address.version != ConnectionAddress.CURRENT_VERSION) {
            return ConnectionResult.fail(
                ConnectionError.UNSUPPORTED_VERSION,
                "Unsupported version '${address.version}'; expected '${ConnectionAddress.CURRENT_VERSION}'"
            )
        }
        if (address.application != application) {
            return ConnectionResult.fail(
                ConnectionError.UNKNOWN_APPLICATION,
                "Unknown application '${address.application}'; this dispatcher serves '$application'"
            )
        }
        if (!registry.hasConnection(address.connection)) {
            return ConnectionResult.fail(
                ConnectionError.UNKNOWN_CONNECTION,
                "Unknown connection '${address.connection}'"
            )
        }

        val handler = registry.handlerFor(address)
            ?: return ConnectionResult.fail(
                ConnectionError.ROUTE_NOT_FOUND,
                "No route for '$address'"
            )

        return try {
            handler.handle(request)
        } catch (e: MissingParamException) {
            ConnectionResult.fail(ConnectionError.INVALID_PARAMS, e.message ?: "Invalid parameters")
        } catch (e: IllegalArgumentException) {
            // Handlers/services signal bad input by throwing IllegalArgumentException.
            ConnectionResult.fail(ConnectionError.INVALID_PARAMS, e.message ?: "Invalid parameters")
        } catch (e: Exception) {
            onHandlerError(address, e)
            ConnectionResult.fail(ConnectionError.HANDLER_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }
}
