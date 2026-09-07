package com.operations.connectkit

/**
 * Holds the [RouteHandler] for every registered `connection/resource/action`. Populated once at
 * startup (see the `local` connection wiring) and read by the [ConnectionDispatcher].
 *
 * Not thread-safe for concurrent registration: build it fully during app construction, then treat
 * it as read-only.
 */
class ConnectionRegistry {
    private val handlers = mutableMapOf<String, RouteHandler>()
    private val connections = mutableSetOf<String>()

    /** Register [handler] for `connection/resource/action`. Re-registering a route replaces it. */
    fun register(connection: String, resource: String, action: String, handler: RouteHandler): ConnectionRegistry {
        connections += connection
        handlers["$connection/$resource/$action"] = handler
        return this
    }

    fun handlerFor(address: ConnectionAddress): RouteHandler? = handlers[address.routeKey]

    /** Whether the connection namespace itself is known — lets the dispatcher tell an unknown
     * connection apart from a known connection missing this particular route. */
    fun hasConnection(connection: String): Boolean = connection in connections

    /** All registered route keys, for diagnostics/tests. */
    val routes: Set<String> get() = handlers.keys.toSet()
}
