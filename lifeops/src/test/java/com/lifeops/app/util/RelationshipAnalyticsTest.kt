package com.lifeops.app.util

import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.Relationship
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RelationshipAnalyticsTest {

    private fun person(id: String, relationship: Relationship? = Relationship.CHILD) =
        Person(id = id, name = id, createdAt = "", relationship = relationship)

    private fun oneOff(date: String, start: Int, end: Int, vararg peopleIds: String) = BusyBlock(
        id = "$date-$start", title = "Time together", startMinutes = start, endMinutes = end,
        daysMask = 0, specificDate = date, createdAt = "", peopleIds = peopleIds.toList()
    )

    private fun weekly(daysMask: Int, start: Int, end: Int, vararg peopleIds: String) = BusyBlock(
        id = "weekly-$daysMask", title = "Standing time", startMinutes = start, endMinutes = end,
        daysMask = daysMask, createdAt = "", peopleIds = peopleIds.toList()
    )

    private val today = LocalDate.parse("2026-08-19") // a Wednesday

    @Test
    fun `bookingStats sums minutes only within the window and finds the most recent date`() {
        val blocks = listOf(
            oneOff("2026-08-18", 600, 660, "jake"),  // yesterday, 60 min, in window
            oneOff("2026-07-01", 600, 660, "jake")   // outside the 30-day window
        )
        val stats = RelationshipAnalytics.bookingStats(blocks, listOf("jake"), today)
        val jake = stats.getValue("jake")
        assertEquals(60, jake.minutesInWindow)
        assertEquals(1, jake.occurrencesInWindow)
        assertEquals(1, jake.daysSinceLastBooked)
    }

    @Test
    fun `bookingStats returns null last-seen when nothing is found in the lookback horizon`() {
        val stats = RelationshipAnalytics.bookingStats(emptyList(), listOf("jake"), today)
        assertNull(stats.getValue("jake").daysSinceLastBooked)
        assertEquals(0, stats.getValue("jake").minutesInWindow)
    }

    @Test
    fun `findImbalances flags a child getting far less time than a sibling as category skew`() {
        val people = listOf(person("jake"), person("emma"))
        val blocks = listOf(
            weekly(0b0011111, 600, 660, "jake"), // Mon-Fri, 60 min/day with Jake
            oneOff("2026-08-17", 600, 610, "emma") // one 10-minute outing with Emma
        )
        val imbalances = RelationshipAnalytics.findImbalances(people, blocks, today)
        val emma = imbalances.firstOrNull { it.person.id == "emma" }
        assertNotNull(emma)
        assertEquals(ImbalanceKind.CATEGORY_SKEW, emma!!.kind)
        assertNull(imbalances.firstOrNull { it.person.id == "jake" })
    }

    @Test
    fun `findImbalances flags a lone tracked person as neglected once past the threshold`() {
        val people = listOf(person("mom", Relationship.PARENT))
        val blocks = listOf(oneOff("2026-07-01", 600, 660, "mom")) // long past 14 days ago
        val imbalances = RelationshipAnalytics.findImbalances(people, blocks, today)
        assertEquals(1, imbalances.size)
        assertEquals(ImbalanceKind.NEGLECTED, imbalances[0].kind)
    }

    @Test
    fun `findImbalances ignores people with no relationship set`() {
        val people = listOf(person("colleague", relationship = null))
        val imbalances = RelationshipAnalytics.findImbalances(people, emptyList(), today)
        assertTrue(imbalances.isEmpty())
    }

    @Test
    fun `findImbalances does not flag someone booked recently and evenly with peers`() {
        val people = listOf(person("jake"), person("emma"))
        val blocks = listOf(
            oneOff("2026-08-18", 600, 660, "jake"),
            oneOff("2026-08-18", 700, 760, "emma")
        )
        val imbalances = RelationshipAnalytics.findImbalances(people, blocks, today)
        assertTrue(imbalances.isEmpty())
    }
}
