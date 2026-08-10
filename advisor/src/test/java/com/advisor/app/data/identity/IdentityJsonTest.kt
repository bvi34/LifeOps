package com.advisor.app.data.identity

import com.advisor.app.logic.Identity
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The identity file is the JSON serialization of [Identity]. This pins the round-trip so an
 * identity edited on disk (or restored from backup) reads back exactly, including lists and the
 * free-form [Identity.traits] map.
 */
class IdentityJsonTest {

    private val gson = Gson()

    @Test
    fun round_trips_through_json() {
        val original = Identity(
            name = "Sam",
            pronouns = "they/them",
            roles = listOf("engineer", "parent"),
            values = listOf("honesty"),
            goals = listOf("ship v2", "read more"),
            focus = "the Advisor app",
            communicationStyle = "direct, sardonic",
            bio = "builds tools for themselves",
            traits = mapOf("timezone" to "UTC-7")
        )
        val restored = gson.fromJson(gson.toJson(original), Identity::class.java)
        assertEquals(original, restored)
    }

    @Test
    fun empty_identity_round_trips() {
        val restored = gson.fromJson(gson.toJson(Identity.EMPTY), Identity::class.java)
        assertEquals(Identity.EMPTY, restored)
    }
}
