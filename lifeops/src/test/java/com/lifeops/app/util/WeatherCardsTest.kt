package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.WeatherAlert
import org.junit.Assert.*
import org.junit.Test

class WeatherCardsTest {

    private fun assessment(rating: OutdoorRating) =
        OutdoorAssessment(
            score = when (rating) {
                OutdoorRating.EXCELLENT -> 10
                OutdoorRating.GOOD -> 40
                OutdoorRating.CAUTION -> 60
                OutdoorRating.AVOID -> 90
            },
            rating = rating,
            positives = listOf("Comfortable temperature (72°)"),
            warnings = emptyList()
        )

    @Test
    fun `warnings come first, most severe first, then morning, then tasks`() {
        val alerts = listOf(
            WeatherAlert("a1", "Heat Advisory", AlertSeverity.MODERATE, "hot", null),
            WeatherAlert("a2", "Tornado Warning", AlertSeverity.EXTREME, "take cover", null)
        )
        val taskCards = listOf(
            WeatherCard.TaskRecommendation("Wash Jeep", "Saturday", 92, listOf("No rain"))
        )
        val cards = WeatherCards.build(alerts, assessment(OutdoorRating.EXCELLENT), "5 PM – 8 PM", taskCards)

        assertEquals(4, cards.size)
        // Two warnings (severe first), then morning, then the task card.
        assertEquals("Tornado Warning", (cards[0] as WeatherCard.Warning).event) // extreme > moderate
        assertEquals("Heat Advisory", (cards[1] as WeatherCard.Warning).event)
        assertTrue(cards[2] is WeatherCard.Morning)
        assertTrue(cards[3] is WeatherCard.TaskRecommendation)
    }

    @Test
    fun `morning card summarizes the rating and best window`() {
        val cards = WeatherCards.build(emptyList(), assessment(OutdoorRating.EXCELLENT), "5 PM – 8 PM")
        assertEquals(1, cards.size)
        val morning = cards.single() as WeatherCard.Morning
        assertEquals("Excellent", morning.ratingLabel)
        assertTrue(morning.summary.contains("Great outdoor day"))
        assertEquals("5 PM – 8 PM", morning.bestWindowLabel)
    }

    @Test
    fun `avoid rating produces a discouraging morning summary`() {
        val cards = WeatherCards.build(emptyList(), assessment(OutdoorRating.AVOID), null)
        val morning = cards.single() as WeatherCard.Morning
        assertTrue(morning.summary.contains("Rough day"))
        assertNull(morning.bestWindowLabel)
    }
}
