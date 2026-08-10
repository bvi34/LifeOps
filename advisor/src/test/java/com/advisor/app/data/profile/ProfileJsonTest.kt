package com.advisor.app.data.profile

import com.advisor.app.logic.Profile
import com.advisor.app.logic.ProfileEntry
import com.advisor.app.logic.ProfileKind
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Profiles are persisted as the JSON serialization of [Profile]. Pin the round-trip (including the
 * enum kind and the entry list) so a hand-edited or restored profile file reads back exactly.
 */
class ProfileJsonTest {

    private val gson = Gson()

    @Test
    fun round_trips_through_json() {
        val original = Profile(
            key = "project-a",
            name = "Project A",
            kind = ProfileKind.PROJECT,
            summary = "the v2 effort",
            entries = listOf(
                ProfileEntry("kickoff notes", ProfileEntry.AUTHOR_USER, 100L),
                ProfileEntry("shipped v2", ProfileEntry.AUTHOR_ADVISOR, 200L)
            ),
            alwaysInclude = true,
            updatedAt = 200L
        )
        val restored = gson.fromJson(gson.toJson(original), Profile::class.java)
        assertEquals(original, restored)
    }
}
