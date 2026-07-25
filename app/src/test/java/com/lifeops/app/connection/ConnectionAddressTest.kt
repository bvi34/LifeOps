package com.lifeops.app.connection

import org.junit.Assert.*
import org.junit.Test

class ConnectionAddressTest {

    @Test
    fun `parses a well-formed address`() {
        val a = ConnectionAddress.parse("/v1/LifeOps/local/task/create")!!
        assertEquals("v1", a.version)
        assertEquals("LifeOps", a.application)
        assertEquals("local", a.connection)
        assertEquals("task", a.resource)
        assertEquals("create", a.action)
    }

    @Test
    fun `tolerates missing leading slash and surrounding whitespace`() {
        val a = ConnectionAddress.parse("  v1/LifeOps/local/task/create/  ")!!
        assertEquals("task", a.resource)
        assertEquals("create", a.action)
    }

    @Test
    fun `routeKey drops version and application`() {
        val a = ConnectionAddress.parse("/v1/LifeOps/local/task/update")!!
        assertEquals("local/task/update", a.routeKey)
    }

    @Test
    fun `toString round-trips the canonical form`() {
        val raw = "/v1/LifeOps/local/task/complete"
        assertEquals(raw, ConnectionAddress.parse(raw)!!.toString())
    }

    @Test
    fun `rejects the wrong number of segments`() {
        assertNull(ConnectionAddress.parse("/v1/LifeOps/local/task"))          // 4
        assertNull(ConnectionAddress.parse("/v1/LifeOps/local/task/create/x")) // 6
        assertNull(ConnectionAddress.parse(""))
    }

    @Test
    fun `rejects blank segments`() {
        assertNull(ConnectionAddress.parse("/v1/LifeOps//task/create"))
        assertNull(ConnectionAddress.parse("/v1/ /local/task/create"))
    }

    @Test
    fun `local factory builds a current-version LifeOps address`() {
        val a = ConnectionAddress.local("task", "create")
        assertEquals("/v1/LifeOps/local/task/create", a.toString())
    }
}
