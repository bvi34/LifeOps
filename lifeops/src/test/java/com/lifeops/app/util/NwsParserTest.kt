package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import org.junit.Assert.*
import org.junit.Test

class NwsParserTest {

    private val pointJson = """
        {
          "properties": {
            "gridId": "TOP",
            "gridX": 31,
            "gridY": 80,
            "forecast": "https://api.weather.gov/gridpoints/TOP/31,80/forecast",
            "forecastHourly": "https://api.weather.gov/gridpoints/TOP/31,80/forecast/hourly",
            "timeZone": "America/Chicago",
            "relativeLocation": {
              "properties": { "city": "Topeka", "state": "KS" }
            }
          }
        }
    """.trimIndent()

    private val forecastJson = """
        {
          "properties": {
            "periods": [
              {
                "name": "This Afternoon",
                "startTime": "2026-07-19T13:00:00-05:00",
                "endTime": "2026-07-19T18:00:00-05:00",
                "isDaytime": true,
                "temperature": 95,
                "temperatureUnit": "F",
                "temperatureTrend": null,
                "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": 20 },
                "relativeHumidity": { "unitCode": "wmoUnit:percent", "value": 55 },
                "windSpeed": "5 to 10 mph",
                "windDirection": "S",
                "shortForecast": "Sunny",
                "detailedForecast": "Sunny, with a high near 95."
              },
              {
                "name": "Tonight",
                "startTime": "2026-07-19T18:00:00-05:00",
                "endTime": "2026-07-20T06:00:00-05:00",
                "isDaytime": false,
                "temperature": 72,
                "temperatureUnit": "F",
                "probabilityOfPrecipitation": { "unitCode": "wmoUnit:percent", "value": null },
                "windSpeed": "Calm",
                "windDirection": "",
                "shortForecast": "Clear"
              }
            ]
          }
        }
    """.trimIndent()

    private val alertsJson = """
        {
          "features": [
            {
              "id": "urn:oid:alert-1",
              "properties": {
                "event": "Heat Advisory",
                "severity": "Moderate",
                "headline": "Heat Advisory until 8 PM",
                "description": "Heat index values up to 105.",
                "instruction": "Drink plenty of fluids.",
                "onset": "2026-07-19T13:00:00-05:00",
                "expires": "2026-07-19T20:00:00-05:00",
                "areaDesc": "Shawnee County"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parseGridPoint pulls grid, urls and place name`() {
        val grid = NwsParser.parseGridPoint(pointJson)
        assertEquals("TOP", grid.gridId)
        assertEquals(31, grid.gridX)
        assertEquals(80, grid.gridY)
        assertTrue(grid.forecastUrl.endsWith("/forecast"))
        assertTrue(grid.forecastHourlyUrl.endsWith("/forecast/hourly"))
        assertEquals("Topeka, KS", grid.placeName)
    }

    @Test
    fun `parseForecastPeriods maps values and nested unit-values`() {
        val periods = NwsParser.parseForecastPeriods(forecastJson)
        assertEquals(2, periods.size)

        val afternoon = periods[0]
        assertEquals("This Afternoon", afternoon.name)
        assertTrue(afternoon.isDaytime)
        assertEquals(95, afternoon.temperatureF)
        assertEquals(20, afternoon.precipitationProbabilityPct)
        assertEquals(55, afternoon.humidityPct)
        assertEquals("Sunny", afternoon.shortForecast)
        // "5 to 10 mph" -> strongest number.
        assertEquals(10, afternoon.wind.speedMph)
        assertEquals("S", afternoon.wind.directionCardinal)

        val tonight = periods[1]
        assertFalse(tonight.isDaytime)
        // null precip value stays null, Calm wind -> 0.
        assertNull(tonight.precipitationProbabilityPct)
        assertEquals(0, tonight.wind.speedMph)
    }

    @Test
    fun `parseAlerts maps severity and fields`() {
        val alerts = NwsParser.parseAlerts(alertsJson)
        assertEquals(1, alerts.size)
        val a = alerts[0]
        assertEquals("urn:oid:alert-1", a.id)
        assertEquals("Heat Advisory", a.event)
        assertEquals(AlertSeverity.MODERATE, a.severity)
        assertEquals("Shawnee County", a.areaDesc)
        assertEquals("2026-07-19T20:00:00-05:00", a.expires)
    }

    @Test
    fun `parseWindMph handles ranges, singles, calm and blanks`() {
        assertEquals(10, NwsParser.parseWindMph("5 to 10 mph"))
        assertEquals(15, NwsParser.parseWindMph("15 mph"))
        assertEquals(0, NwsParser.parseWindMph("Calm"))
        assertEquals(0, NwsParser.parseWindMph(null))
        assertEquals(0, NwsParser.parseWindMph(""))
    }

    @Test
    fun `empty or malformed payloads degrade to empty lists`() {
        assertTrue(NwsParser.parseForecastPeriods("""{"properties":{}}""").isEmpty())
        assertTrue(NwsParser.parseAlerts("""{"features":[]}""").isEmpty())
    }
}
