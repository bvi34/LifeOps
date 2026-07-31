package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.Wind
import org.junit.Assert.*
import org.junit.Test

class OutdoorScoreTest {

    @Test
    fun `rating bands map to the roadmap thresholds`() {
        assertEquals(OutdoorRating.EXCELLENT, OutdoorRating.fromScore(0))
        assertEquals(OutdoorRating.EXCELLENT, OutdoorRating.fromScore(25))
        assertEquals(OutdoorRating.GOOD, OutdoorRating.fromScore(26))
        assertEquals(OutdoorRating.GOOD, OutdoorRating.fromScore(50))
        assertEquals(OutdoorRating.CAUTION, OutdoorRating.fromScore(51))
        assertEquals(OutdoorRating.CAUTION, OutdoorRating.fromScore(75))
        assertEquals(OutdoorRating.AVOID, OutdoorRating.fromScore(76))
        assertEquals(OutdoorRating.AVOID, OutdoorRating.fromScore(100))
    }

    @Test
    fun `mild day scores excellent with positive reasons`() {
        val a = OutdoorScore.score(
            feelsLikeF = 70, humidityPct = 45, uvIndex = 4, windMph = 5,
            rainProbabilityPct = 0, stormRisk = false
        )
        assertEquals(OutdoorRating.EXCELLENT, a.rating)
        assertTrue(a.score <= 25)
        assertTrue(a.positives.any { it.contains("Comfortable") })
        assertTrue(a.warnings.isEmpty())
    }

    @Test
    fun `hot humid high-UV day is avoid`() {
        val a = OutdoorScore.score(
            feelsLikeF = 105, humidityPct = 75, uvIndex = 9, windMph = 5,
            rainProbabilityPct = 10, stormRisk = false
        )
        assertEquals(OutdoorRating.AVOID, a.rating)
        assertTrue(a.warnings.any { it.contains("Hot") })
        assertTrue(a.warnings.any { it.contains("UV") })
    }

    @Test
    fun `storm risk alone pushes an otherwise nice day to avoid`() {
        val nice = OutdoorScore.score(70, 45, 4, 5, 0, stormRisk = false)
        val stormy = OutdoorScore.score(70, 45, 4, 5, 0, stormRisk = true)
        assertEquals(OutdoorRating.EXCELLENT, nice.rating)
        assertEquals(OutdoorRating.AVOID, stormy.rating)
        assertTrue(stormy.warnings.any { it.contains("Storm") })
    }

    @Test
    fun `cold and windy lands in caution`() {
        val a = OutdoorScore.score(
            feelsLikeF = 38, humidityPct = null, uvIndex = null, windMph = 20,
            rainProbabilityPct = 10, stormRisk = false
        )
        assertEquals(OutdoorRating.CAUTION, a.rating)
        assertTrue(a.warnings.any { it.contains("Cold") })
        assertTrue(a.warnings.any { it.contains("Windy") })
    }

    @Test
    fun `score never exceeds 100`() {
        val a = OutdoorScore.score(120, 95, 12, 40, 100, stormRisk = true)
        assertEquals(100, a.score)
        assertEquals(OutdoorRating.AVOID, a.rating)
    }

    @Test
    fun `storm risk is detected from forecast text`() {
        assertTrue(OutdoorScore.stormRiskFromText("Scattered Thunderstorms"))
        assertTrue(OutdoorScore.stormRiskFromText("Severe storm likely"))
        assertFalse(OutdoorScore.stormRiskFromText("Sunny"))
        assertFalse(OutdoorScore.stormRiskFromText(null))
    }

    @Test
    fun `forCurrent folds in a severe alert as storm risk`() {
        val current = CurrentConditions(
            temperatureF = 72, feelsLikeF = 72, humidityPct = 40,
            wind = Wind(5, "S"), precipitationProbabilityPct = 0,
            uvIndex = 3, shortForecast = "Sunny", observedAt = "2026-07-19T12:00:00Z"
        )
        val calm = OutdoorScore.forCurrent(current, emptyList())
        val withAlert = OutdoorScore.forCurrent(
            current,
            listOf(
                WeatherAlert(
                    id = "a1", event = "Severe Thunderstorm Warning",
                    severity = AlertSeverity.SEVERE, headline = null, description = null
                )
            )
        )
        assertEquals(OutdoorRating.EXCELLENT, calm.rating)
        assertEquals(OutdoorRating.AVOID, withAlert.rating)
    }
}
