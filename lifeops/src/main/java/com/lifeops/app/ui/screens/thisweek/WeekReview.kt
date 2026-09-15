@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lifeops.app.ui.screens.thisweek

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.util.ReviewMetric
import com.lifeops.app.util.Trend
import com.lifeops.app.util.WeekReview

/**
 * Closing the week: the honest mirror, and the numbers it is drawn from.
 */

/**
 * The "week in review" body: headline metrics with honest deltas, any grey-scar aspects, and the
 * earned observations. Everything here comes from [WeekReviewBuilder] — this only lays it out.
 */
@Composable
internal fun WeekReviewSection(review: WeekReview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        review.headline.forEach { metric -> MetricRow(metric) }

        // The verdict on the bar, when one was set. It sits above the observations rather than
        // among them because a clean pass is the week's *result*, not a remark about it — and
        // because it must never be crowded out by the three-observation cap. The miss is stated
        // in the observations instead, where the mirror's sharper lines live.
        if (review.commitmentMet == true) {
            Text(
                "The bar you set was cleared. Rest is earned.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Neglected aspects the Growth Record will scar — surfaced from 2 weeks grey.
        review.aspectBalance.filter { it.greyStreak >= 2 }.forEach { row ->
            Text(
                "△ ${row.name} — grey ${row.greyStreak} week${if (row.greyStreak == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            )
        }

        if (review.observations.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            review.observations.forEach { obs ->
                Text(
                    obs,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/** One headline metric: label on the left, value and (if there's history) a coloured delta on the right. */
@Composable
private fun MetricRow(metric: ReviewMetric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            metric.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                metric.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            metric.delta?.let { delta ->
                val improved: Boolean? = when (metric.trend) {
                    Trend.UP -> metric.higherIsBetter
                    Trend.DOWN -> !metric.higherIsBetter
                    Trend.FLAT -> null
                }
                val arrow = when (metric.trend) {
                    Trend.UP -> "▲"
                    Trend.DOWN -> "▼"
                    Trend.FLAT -> ""
                }
                val color = when (improved) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
                Text(
                    "$arrow $delta".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}
