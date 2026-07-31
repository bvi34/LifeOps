package com.lifeops.app.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectionDispatcherTest {

    /** A registry with one echo route: local/thing/do returns the params it received. */
    private fun dispatcher(): ConnectionDispatcher {
        val registry = ConnectionRegistry()
        registry.register("local", "thing", "do") { req ->
            ConnectionResult.ok("echo" to req.params.getString("value"))
        }
        registry.register("local", "thing", "boom") {
            throw IllegalStateException("kaboom")
        }
        registry.register("local", "thing", "needs") { req ->
            ConnectionResult.ok("v" to req.params.requireString("required"))
        }
        return ConnectionDispatcher(registry)
    }

    @Test
    fun `dispatches to the registered handler and passes params`() = runTest {
        val result = dispatcher().dispatch(
            "/v1/LifeOps/local/thing/do",
            ConnectionParams.of("value" to "hi")
        )
        assertTrue(result is ConnectionResult.Success)
        assertEquals("hi", (result as ConnectionResult.Success)["echo"])
    }

    @Test
    fun `malformed address fails without hitting a handler`() = runTest {
        val result = dispatcher().dispatch("/v1/LifeOps/local/thing")
        assertEquals(ConnectionError.MALFORMED_ADDRESS, (result as ConnectionResult.Failure).error)
    }

    @Test
    fun `unsupported version is rejected`() = runTest {
        val result = dispatcher().dispatch("/v2/LifeOps/local/thing/do")
        assertEquals(ConnectionError.UNSUPPORTED_VERSION, (result as ConnectionResult.Failure).error)
    }

    @Test
    fun `unknown application is rejected`() = runTest {
        val result = dispatcher().dispatch("/v1/OtherApp/local/thing/do")
        assertEquals(ConnectionError.UNKNOWN_APPLICATION, (result as ConnectionResult.Failure).error)
    }

    @Test
    fun `unknown connection is distinguished from a missing route`() = runTest {
        val unknownConn = dispatcher().dispatch("/v1/LifeOps/remote/thing/do")
        assertEquals(ConnectionError.UNKNOWN_CONNECTION, (unknownConn as ConnectionResult.Failure).error)

        val missingRoute = dispatcher().dispatch("/v1/LifeOps/local/thing/undefined")
        assertEquals(ConnectionError.ROUTE_NOT_FOUND, (missingRoute as ConnectionResult.Failure).error)
    }

    @Test
    fun `missing required param becomes INVALID_PARAMS`() = runTest {
        val result = dispatcher().dispatch("/v1/LifeOps/local/thing/needs", ConnectionParams.EMPTY)
        val failure = result as ConnectionResult.Failure
        assertEquals(ConnectionError.INVALID_PARAMS, failure.error)
        assertTrue(failure.message.contains("required"))
    }

    @Test
    fun `handler exception becomes HANDLER_ERROR`() = runTest {
        val result = dispatcher().dispatch("/v1/LifeOps/local/thing/boom")
        assertEquals(ConnectionError.HANDLER_ERROR, (result as ConnectionResult.Failure).error)
    }
}
