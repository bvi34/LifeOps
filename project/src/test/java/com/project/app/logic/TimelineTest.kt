package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private fun event(
        id: String,
        title: String = id,
        whenLabel: String? = null,
        era: String? = null,
        order: Int = 0
    ) = TimelineEvent(id, title, null, era, whenLabel, order)

    @Test
    fun `a when-label is read on whichever scale it is written on`() {
        assertEquals(WhenScale.DATE, Timeline.parseWhen("2026-03-04")!!.scale)
        assertEquals(WhenScale.DAY, Timeline.parseWhen("Day 3")!!.scale)
        assertEquals(3L, Timeline.parseWhen("D3")!!.value)
        assertEquals(WhenScale.CHAPTER, Timeline.parseWhen("Chapter 4")!!.scale)
        assertEquals(WhenScale.YEAR, Timeline.parseWhen("Year 412")!!.scale)
        assertEquals(412L, Timeline.parseWhen("412 AD")!!.value)
    }

    @Test
    fun `BC years sort before AD ones`() {
        val bc = Timeline.parseWhen("44 BC")!!
        val ad = Timeline.parseWhen("14 AD")!!

        assertEquals(-44L, bc.value)
        assertTrue(bc.value < ad.value)
    }

    @Test
    fun `a vague when-label is not an error`() {
        assertNull(Timeline.parseWhen("the night before the coronation"))
        assertNull(Timeline.parseWhen(null))
        assertNull(Timeline.parseWhen("   "))
    }

    @Test
    fun `the author's order is the timeline, whatever the labels say`() {
        val events = listOf(
            event("b", whenLabel = "Day 1", order = 1),
            event("a", whenLabel = "Day 9", order = 0)
        )

        assertEquals(listOf("a", "b"), Timeline.rows(events).map { it.event.id })
    }

    @Test
    fun `a label that contradicts the order is flagged, not silently corrected`() {
        val events = listOf(
            event("battle", whenLabel = "Year 412", order = 0),
            event("coronation", whenLabel = "Year 409", order = 1)
        )

        val rows = Timeline.rows(events)

        assertFalse(rows[0].contradictsOrder)
        assertTrue(rows[1].contradictsOrder)
        assertEquals(-3L, rows[1].gapFromPrevious)
        assertEquals(listOf("coronation"), Timeline.contradictions(events))
    }

    @Test
    fun `a chapter going backwards is a flashback, not a contradiction`() {
        val events = listOf(
            event("now", whenLabel = "Chapter 8", order = 0),
            event("then", whenLabel = "Chapter 2", order = 1)
        )

        assertEquals(emptyList<String>(), Timeline.contradictions(events))
    }

    @Test
    fun `gaps are only computed between labels on the same scale`() {
        val events = listOf(
            event("a", whenLabel = "Day 1", order = 0),
            event("b", whenLabel = "Year 412", order = 1),
            event("c", whenLabel = "Year 415", order = 2)
        )

        val rows = Timeline.rows(events)

        assertNull(rows[0].gapFromPrevious)
        assertNull(rows[1].gapFromPrevious)
        assertEquals(3L, rows[2].gapFromPrevious)
        assertNull(Timeline.scaleOf(events))
    }

    @Test
    fun `a vague label does not break the comparison of the events around it`() {
        val events = listOf(
            event("a", whenLabel = "Day 1", order = 0),
            event("b", whenLabel = "some time later", order = 1),
            event("c", whenLabel = "Day 6", order = 2)
        )

        val rows = Timeline.rows(events)

        assertNull(rows[1].parsed)
        assertNull(rows[1].gapFromPrevious)
        assertEquals(5L, rows[2].gapFromPrevious)
    }

    @Test
    fun `eras band consecutive runs, and a recurring era is a second band`() {
        val events = listOf(
            event("a", era = "Before", order = 0),
            event("b", era = "Before", order = 1),
            event("c", era = "The War", order = 2),
            event("d", era = "Before", order = 3)
        )

        val spans = Timeline.eras(events)

        assertEquals(listOf("Before", "The War", "Before"), spans.map { it.label })
        assertEquals(listOf(2, 1, 1), spans.map { it.count })
        assertEquals(listOf(true, false, true, true), Timeline.rows(events).map { it.startsEra })
    }

    @Test
    fun `auto-sort is offered only when every label reads on one scale`() {
        val sortable = listOf(
            event("a", whenLabel = "Day 9", order = 0),
            event("b", whenLabel = "Day 1", order = 1)
        )
        val mixed = listOf(
            event("a", whenLabel = "Day 9", order = 0),
            event("b", whenLabel = "Year 412", order = 1)
        )
        val vague = listOf(
            event("a", whenLabel = "Day 9", order = 0),
            event("b", whenLabel = "later", order = 1)
        )

        assertTrue(Timeline.canAutoSort(sortable))
        assertFalse(Timeline.canAutoSort(mixed))
        assertFalse(Timeline.canAutoSort(vague))

        assertEquals(listOf("b", "a"), Timeline.autoSorted(sortable).sortedBy { it.order }.map { it.id })
        // Not sortable: the manual order stands, unchanged.
        assertEquals(listOf("a", "b"), Timeline.autoSorted(vague).map { it.id })
    }

    @Test
    fun `move renumbers the run and an impossible move changes nothing`() {
        val events = listOf(
            event("a", order = 0),
            event("b", order = 1),
            event("c", order = 2)
        )

        val changed = Timeline.move(events, "c", -1)
        val applied = events.map { original -> changed.firstOrNull { it.id == original.id } ?: original }

        assertEquals(listOf("a", "c", "b"), Timeline.ordered(applied).map { it.id })
        assertEquals(emptyList<TimelineEvent>(), Timeline.move(events, "a", -1))
        assertEquals(emptyList<TimelineEvent>(), Timeline.move(events, "ghost", 1))
    }

    @Test
    fun `nextOrder appends`() {
        assertEquals(0, Timeline.nextOrder(emptyList()))
        assertEquals(3, Timeline.nextOrder(listOf(event("a", order = 2))))
    }
}
