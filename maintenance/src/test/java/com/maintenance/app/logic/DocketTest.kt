package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocketTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun entry(
        id: String,
        status: DueStatus,
        dueAt: Long?,
        asset: String = "Truck",
        title: String = "Something",
        source: DocketSource = DocketSource.UPKEEP
    ) = DocketEntry(id, "a-$asset", asset, title, "", status, dueAt, source)

    @Test
    fun `status leads the order, not the date`() {
        val ordered = Docket.order(
            listOf(
                entry("scheduled", DueStatus.SCHEDULED, now + 40 * day),
                entry("overdue", DueStatus.OVERDUE, now - 2 * day),
                entry("soon", DueStatus.DUE_SOON, now + 3 * day)
            )
        )

        assertEquals(listOf("overdue", "soon", "scheduled"), ordered.map { it.id })
    }

    @Test
    fun `the most recently missed is the top of the overdue list`() {
        val ordered = Docket.order(
            listOf(
                entry("march", DueStatus.OVERDUE, now - 200 * day),
                entry("last week", DueStatus.OVERDUE, now - 7 * day),
                entry("yesterday", DueStatus.OVERDUE, now - day)
            )
        )

        assertEquals(listOf("yesterday", "last week", "march"), ordered.map { it.id })
    }

    @Test
    fun `everything else reads soonest first, and undated lines sit at the back`() {
        val ordered = Docket.order(
            listOf(
                entry("undated", DueStatus.SCHEDULED, null),
                entry("later", DueStatus.SCHEDULED, now + 90 * day),
                entry("sooner", DueStatus.SCHEDULED, now + 20 * day)
            )
        )

        assertEquals(listOf("sooner", "later", "undated"), ordered.map { it.id })
    }

    @Test
    fun `the ones without a schedule sit below the ones with one`() {
        val ordered = Docket.order(
            listOf(
                entry("dormant", DueStatus.DORMANT, null),
                entry("baseline", DueStatus.NEEDS_BASELINE, null),
                entry("scheduled", DueStatus.SCHEDULED, now + 900 * day)
            )
        )

        assertEquals(listOf("scheduled", "baseline", "dormant"), ordered.map { it.id })
    }

    @Test
    fun `only the pressing lines are worth interrupting somebody about`() {
        val entries = listOf(
            entry("overdue", DueStatus.OVERDUE, now - day),
            entry("soon", DueStatus.DUE_SOON, now + day),
            entry("scheduled", DueStatus.SCHEDULED, now + 200 * day),
            entry("dormant", DueStatus.DORMANT, null)
        )

        assertEquals(listOf("overdue", "soon"), Docket.pressing(entries).map { it.id })
        assertEquals("1 overdue · 1 due soon", Docket.headline(entries))
    }

    @Test
    fun `an empty docket looks empty rather than reporting zeroes`() {
        assertEquals("Nothing due", Docket.headline(emptyList()))
        assertEquals(
            "Nothing due",
            Docket.headline(listOf(entry("scheduled", DueStatus.SCHEDULED, now + 90 * day)))
        )
        assertTrue(Docket.pressing(emptyList()).isEmpty())
    }

    @Test
    fun `two lines that fall due together sort by asset, then by job`() {
        val ordered = Docket.order(
            listOf(
                entry("b", DueStatus.DUE_SOON, now + day, asset = "Truck", title = "Wipers"),
                entry("a", DueStatus.DUE_SOON, now + day, asset = "Truck", title = "Oil"),
                entry("c", DueStatus.DUE_SOON, now + day, asset = "Furnace", title = "Filter")
            )
        )

        assertEquals(listOf("c", "a", "b"), ordered.map { it.id })
    }
}
