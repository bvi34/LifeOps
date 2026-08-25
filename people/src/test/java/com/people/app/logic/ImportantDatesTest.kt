package com.people.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ImportantDatesTest {

    private fun date(text: String) = LocalDate.parse(text)

    @Test
    fun `the next occurrence rolls into next year once it has passed`() {
        assertEquals(date("2026-12-25"), ImportantDates.nextOccurrence("12-25", date("2026-08-25")))
        assertEquals(date("2027-04-02"), ImportantDates.nextOccurrence("04-02", date("2026-08-25")))
    }

    @Test
    fun `today counts as next, not as a year away`() {
        assertEquals(date("2026-08-25"), ImportantDates.nextOccurrence("08-25", date("2026-08-25")))
    }

    @Test
    fun `a 29 February date still happens in a common year`() {
        // 2027 is not a leap year: the birthday lands on the 28th rather than vanishing or throwing.
        assertEquals(date("2027-02-28"), ImportantDates.nextOccurrence("02-29", date("2026-08-25")))
        // In a leap year it is itself.
        assertEquals(date("2028-02-29"), ImportantDates.nextOccurrence("02-29", date("2027-06-01")))
    }

    @Test
    fun `month-day parsing keeps real dates and rejects invented ones`() {
        assertEquals(2 to 29, ImportantDates.parseMonthDay("02-29"))
        assertNull(ImportantDates.parseMonthDay("04-31"))
        assertNull(ImportantDates.parseMonthDay("13-01"))
        assertNull(ImportantDates.parseMonthDay("2019-04-02"))
        assertNull(ImportantDates.parseMonthDay(null))
    }

    @Test
    fun `a birth date reduces to the day it comes round on`() {
        assertEquals("04-02", ImportantDates.monthDayOf("2019-04-02"))
        assertNull(ImportantDates.monthDayOf("nonsense"))
    }

    @Test
    fun `a known year says the age being turned`() {
        val resolved = ImportantDates.resolve(
            personId = "p1",
            label = "Ellie's birthday",
            kind = DateKind.BIRTHDAY,
            monthDay = "04-02",
            year = 2019,
            from = date("2026-08-25")
        )!!
        assertEquals(date("2027-04-02"), resolved.next)
        assertEquals(8, resolved.turning)
        assertEquals(220, resolved.daysUntil)
    }

    @Test
    fun `an unknown or impossible year simply doesn't claim an age`() {
        val noYear = ImportantDates.resolve("p1", "Bin day", DateKind.OTHER, "04-02", null, date("2026-08-25"))!!
        assertNull(noYear.turning)

        val futureYear = ImportantDates.resolve("p1", "?", DateKind.BIRTHDAY, "04-02", 2099, date("2026-08-25"))!!
        assertNull(futureYear.turning)
    }

    @Test
    fun `upcoming keeps the near ones in order`() {
        val from = date("2026-08-25")
        val dates = listOfNotNull(
            ImportantDates.resolve("a", "far", DateKind.OTHER, "12-25", null, from),
            ImportantDates.resolve("b", "soon", DateKind.BIRTHDAY, "09-01", null, from),
            ImportantDates.resolve("c", "today", DateKind.OTHER, "08-25", null, from)
        )
        assertEquals(listOf("today", "soon"), ImportantDates.upcoming(dates, withinDays = 30).map { it.label })
    }

    @Test
    fun `the countdown reads the way a person would say it`() {
        assertEquals("today", ImportantDates.describe(0))
        assertEquals("tomorrow", ImportantDates.describe(1))
        assertEquals("in 9 days", ImportantDates.describe(9))
        assertEquals("in 3 weeks", ImportantDates.describe(18))
        assertEquals("in 4 months", ImportantDates.describe(120))
    }
}
