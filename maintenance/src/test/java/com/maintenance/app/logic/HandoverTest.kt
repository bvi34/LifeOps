package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The file you hand to the buyer.
 *
 * Everything asserted here is about the file being read by something that is not this app — a
 * spreadsheet, and a stranger.
 */
class HandoverTest {

    private val utc = ZoneId.of("UTC")

    /** 2023-11-14, 22:13 UTC. */
    private val now = 1_700_000_000_000L
    private val day = Upkeep.DAY_MILLIS

    private fun row(
        daysAgo: Long,
        title: String,
        vendor: String? = null,
        meter: Long? = null,
        cents: Long = 0L,
        notes: String? = null
    ) = HandoverRow(now - daysAgo * day, title, vendor, meter, cents, notes)

    @Test
    fun `the history reads newest first, with a named meter column`() {
        val csv = Handover.csv(
            rows = listOf(
                row(daysAgo = 30, title = "Tyres", vendor = "Dave's", meter = 41_000, cents = 62_000),
                row(daysAgo = 1, title = "Engine oil & filter", vendor = "Quick Lube", meter = 42_100, cents = 8_995)
            ),
            meterUnit = MeterUnit.MILES,
            zone = utc
        )

        assertEquals(
            listOf(
                "Date,What,Vendor,Odometer,Cost,Notes",
                "2023-11-13,Engine oil & filter,Quick Lube,42100,89.95,",
                "2023-10-15,Tyres,Dave's,41000,620.00,"
            ),
            csv.lines()
        )
    }

    @Test
    fun `money is plain so a column of it adds up`() {
        // No symbol, no thousands comma — both of which make a spreadsheet cell text rather than
        // a number.
        assertEquals("1234.56", Money.plain(123_456))
        assertEquals("0.00", Money.plain(0))
        assertEquals("-12.05", Money.plain(-1_205))
    }

    @Test
    fun `a note with a comma in it stays one column`() {
        val csv = Handover.csv(
            rows = listOf(row(daysAgo = 1, title = "Belt", notes = "replaced belt, cheaper than the dealer")),
            meterUnit = null,
            zone = utc
        )

        assertEquals(
            "2023-11-13,Belt,,,0.00,\"replaced belt, cheaper than the dealer\"",
            csv.lines()[1]
        )
    }

    @Test
    fun `a quote inside a field is doubled, and a newline survives inside its cell`() {
        val csv = Handover.csv(
            rows = listOf(row(daysAgo = 1, title = "Bodywork", vendor = "Bob \"the panel\" Smith", notes = "two\nlines")),
            meterUnit = null,
            zone = utc
        )

        assertEquals(
            "2023-11-13,Bodywork,\"Bob \"\"the panel\"\" Smith\",,0.00,\"two\nlines\"",
            csv.substringAfter("\n")
        )
    }

    @Test
    fun `a history with nothing in it is a header, not an empty file`() {
        // An empty file looks like a failed export. A header with no rows says what happened.
        assertEquals("Date,What,Vendor,Meter,Cost,Notes", Handover.csv(emptyList(), meterUnit = null))
    }

    @Test
    fun `the file names itself after the thing and the day`() {
        assertEquals(
            "2018-jeep-wrangler-service-history-2026-09-02.csv",
            Handover.fileName("2018 Jeep Wrangler", LocalDate.of(2026, 9, 2))
        )
        // Punctuation and runs of it collapse rather than reaching a file system.
        assertEquals(
            "dave-s-mower-service-history-2026-09-02.csv",
            Handover.fileName("Dave's  mower!!", LocalDate.of(2026, 9, 2))
        )
        assertEquals(
            "asset-service-history-2026-09-02.csv",
            Handover.fileName("   ", LocalDate.of(2026, 9, 2))
        )
    }
}
