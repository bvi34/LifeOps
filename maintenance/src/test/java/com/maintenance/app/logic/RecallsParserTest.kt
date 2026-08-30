package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fixture trimmed from a real `recallsByVehicle` response for a 2018 Wrangler. */
class RecallsParserTest {

    private val payload = """
        {
          "Count": 3,
          "results": [
            {
              "Manufacturer": "Chrysler (FCA US LLC)",
              "NHTSACampaignNumber": "19V680000",
              "parkIt": "False",
              "parkOutSide": "False",
              "ReportReceivedDate": "26/09/2019",
              "Component": "SEAT BELTS:FRONT:ANCHORAGE",
              "Summary": "Chrysler is recalling certain 2011-2018 Jeep Wrangler right hand drive vehicles.",
              "Consequence": "A severed seat belt buckle strap will result in an inoperative seat belt.",
              "Remedy": "Dealers will replace the driver's seat belt buckle, free of charge.",
              "ModelYear": "2018", "Make": "JEEP", "Model": "WRANGLER"
            },
            {
              "Manufacturer": "Chrysler (FCA US LLC)",
              "NHTSACampaignNumber": "18V675000",
              "parkIt": "False",
              "parkOutSide": "True",
              "ReportReceivedDate": "27/09/2018",
              "Component": "STRUCTURE:FRAME AND MEMBERS",
              "Summary": "The front track bar bracket may not be properly welded.",
              "ModelYear": "2018", "Make": "JEEP", "Model": "WRANGLER"
            },
            {
              "NHTSACampaignNumber": "",
              "Component": "NOTHING",
              "Summary": "A row with no campaign number is not a recall."
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a recall keeps the manufacturer's own words`() {
        val recalls = RecallsParser.parse(payload)

        val belts = recalls.first { it.campaignNumber == "19V680000" }
        assertEquals("SEAT BELTS:FRONT:ANCHORAGE", belts.component)
        assertTrue(belts.summary.startsWith("Chrysler is recalling"))
        assertTrue(belts.remedy!!.contains("free of charge"))
        assertEquals(LocalDate.of(2019, 9, 26), belts.reportedOn)
        assertEquals("Chrysler (FCA US LLC)", belts.manufacturer)
    }

    @Test
    fun `the two flags that mean stop are read, and lead`() {
        val recalls = RecallsParser.parse(payload)

        val frame = recalls.first { it.campaignNumber == "18V675000" }
        assertTrue(frame.parkOutside)
        assertTrue(frame.isUrgent)
        assertEquals("Do not park indoors · Structure · Frame and members", frame.headline)

        // Urgent first, even though it is the older of the two.
        assertEquals("18V675000", recalls.first().campaignNumber)
    }

    @Test
    fun `an ordinary recall reads as its component`() {
        val belts = RecallsParser.parse(payload).first { it.campaignNumber == "19V680000" }

        assertTrue(!belts.isUrgent)
        assertEquals("Seat belts · Front · Anchorage", belts.headline)
    }

    @Test
    fun `a row with no campaign number is dropped rather than shown as blank`() {
        assertEquals(listOf("18V675000", "19V680000"), RecallsParser.parse(payload).map { it.campaignNumber })
    }

    @Test
    fun `an unreadable payload is no recalls, not a crash`() {
        assertTrue(RecallsParser.parse("not json").isEmpty())
        assertTrue(RecallsParser.parse("""{"Count":0,"results":[]}""").isEmpty())
        assertTrue(RecallsParser.parse("{}").isEmpty())
    }
}
