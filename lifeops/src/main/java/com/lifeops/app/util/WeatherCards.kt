package com.lifeops.app.util

import com.lifeops.app.data.model.WeatherAlert

/**
 * Builds the Phase 3 "dynamic LifeOps cards" from already-computed weather state — pure and
 * testable, no Android. The screen renders whatever list this returns, in order.
 *
 * Ordering reflects urgency: active-alert warnings first (most severe first), then the morning
 * "today's conditions" card, then one recommendation card per outdoor task.
 */
sealed class WeatherCard {
    /** "Today's Conditions" — the overall outdoor read plus the best window if there is one. */
    data class Morning(
        val ratingLabel: String,
        val summary: String,
        val bestWindowLabel: String?
    ) : WeatherCard()

    /** Per-task "best time" suggestion, e.g. Wash Jeep → Saturday · 92%. */
    data class TaskRecommendation(
        val taskTitle: String,
        val windowLabel: String,
        val matchPercent: Int,
        val reasons: List<String>
    ) : WeatherCard()

    /** Severe-weather warning surfaced from an active NWS alert. */
    data class Warning(
        val event: String,
        val detail: String,
        val severityLabel: String
    ) : WeatherCard()

    /** Actionable severe-weather intelligence, e.g. "Storm approaching · Delay ~90 min". */
    data class Advisory(
        val headline: String,
        val detail: String,
        val delayHint: String?
    ) : WeatherCard()
}

object WeatherCards {

    fun build(
        alerts: List<WeatherAlert>,
        assessment: OutdoorAssessment,
        bestWindowLabel: String?,
        taskRecommendations: List<WeatherCard.TaskRecommendation> = emptyList(),
        advisories: List<WeatherAdvisory> = emptyList()
    ): List<WeatherCard> {
        val cards = mutableListOf<WeatherCard>()

        // 1. Warnings, most severe first.
        alerts.sortedByDescending { it.severity.rank }.forEach { alert ->
            cards += WeatherCard.Warning(
                event = alert.event.ifBlank { "Weather alert" },
                detail = alert.headline ?: alert.description?.take(160).orEmpty(),
                severityLabel = alert.severity.value
            )
        }

        // 2. Actionable advisories (severe-weather intelligence), most severe first.
        advisories.sortedByDescending { it.severityRank }.forEach { adv ->
            cards += WeatherCard.Advisory(adv.headline, adv.detail, adv.delayHint)
        }

        // 3. Morning conditions card.
        cards += WeatherCard.Morning(
            ratingLabel = assessment.rating.label,
            summary = morningSummary(assessment),
            bestWindowLabel = bestWindowLabel
        )

        // 4. Per-task recommendations.
        cards += taskRecommendations

        return cards
    }

    private fun morningSummary(assessment: OutdoorAssessment): String {
        val lead = when (assessment.rating) {
            OutdoorRating.EXCELLENT -> "Great outdoor day"
            OutdoorRating.GOOD -> "Good day to be outside"
            OutdoorRating.CAUTION -> "Mixed — take care outside"
            OutdoorRating.AVOID -> "Rough day for outdoor plans"
        }
        val detail = (assessment.warnings.firstOrNull() ?: assessment.positives.firstOrNull())
        return if (detail != null) "$lead · $detail" else lead
    }
}
