package com.lifeops.app.ui.screens.weather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.DayRow
import com.lifeops.app.util.HourRow
import com.lifeops.app.util.OutdoorAssessment
import com.lifeops.app.util.OutdoorRating
import com.lifeops.app.util.RecommendedWindow
import com.lifeops.app.util.WeatherAdvisory
import com.lifeops.app.util.WeatherGlyph
import com.lifeops.app.util.WeatherMetric

/**
 * The pieces the detailed weather screen is built from. Each one renders a slice of the already-
 * decided [com.lifeops.app.util.WeatherDetailView]; none of them works anything out.
 */

/** The reading itself, at the size it deserves when it is the whole point of the screen. */
@Composable
fun DetailHeaderCard(
    report: WeatherReport,
    assessment: OutdoorAssessment?,
    placeName: String,
    onOpenRadar: () -> Unit
) {
    val current = report.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(placeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    WeatherGlyph.forShortForecast(current.shortForecast),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${current.temperatureF}°",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(current.shortForecast, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Feels like ${current.feelsLikeF}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                assessment?.rating?.let { RatingBadge(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Updated ${DateUtil.formatInstant(report.fetchedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenRadar) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Radar")
                }
            }
        }
    }
}

/** "Rain likely around 4 PM" — the one sentence worth interrupting the layout for. */
@Composable
fun RainCalloutCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🌧️", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * An active NWS alert in full. Collapsed it is the headline you already saw on the weather screen;
 * expanded it is the official text — including the *instruction*, which is the part that tells you
 * what to actually do and which no summary should ever paraphrase.
 */
@Composable
fun AlertDetailCard(alert: WeatherAlert) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "⚠ ${alert.event.ifBlank { "Weather alert" }}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        listOfNotNull(
                            alert.severity.value,
                            alert.expires?.let { "until ${DateUtil.formatInstant(it)}" },
                            alert.areaDesc?.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            alert.headline?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    alert.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    alert.instruction?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/** "Storm approaching · clearing by 4 PM" with its delay hint. */
@Composable
fun AdvisoryDetailCard(advisory: WeatherAdvisory) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    advisory.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                advisory.delayHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Text(
                advisory.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/** The readings that don't fit on the header's one line, two to a row. */
@Composable
fun MetricGrid(metrics: List<WeatherMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { metric ->
                    MetricTile(metric, Modifier.weight(1f))
                }
                // Keeps a lone final tile half-width instead of stretching it across the row.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(metric: WeatherMetric, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(
                metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(metric.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            metric.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

/**
 * The next day, hour by hour. The little column under each temperature is the same number drawn
 * against the window's own range — the shape of the day is the thing a list of numbers hides.
 */
@Composable
fun HourlyStrip(hours: List<HourRow>, lowF: Int?, highF: Int?) {
    val span = ((highF ?: 0) - (lowF ?: 0)).coerceAtLeast(1)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(hours) { hour ->
            val fraction = ((hour.temperatureF - (lowF ?: hour.temperatureF)).toFloat() / span)
                .coerceIn(0f, 1f)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hour.isNow) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    Modifier.width(62.dp).padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        hour.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (hour.isNow) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Text(hour.glyph, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${hour.temperatureF}°",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        Modifier.height(30.dp).width(6.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.2f + 0.8f * fraction)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        hour.precipitationPct?.let { "$it%" } ?: "–",
                        style = MaterialTheme.typography.labelSmall,
                        color = if ((hour.precipitationPct ?: 0) >= 40) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * One row per day. The bar is each day's high and low placed inside the whole week's range, so the
 * cold snap on Thursday is visible as a bar that sits lower — not as two numbers you have to
 * compare in your head.
 */
@Composable
fun DailyRow(row: DayRow, weekLowF: Int, weekHighF: Int) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.glyph, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        row.shortForecast,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                row.precipitationPct?.takeIf { it > 0 }?.let {
                    Text(
                        "$it%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    buildString {
                        append(row.highF?.let { "$it°" } ?: "—")
                        append(" / ")
                        append(row.lowF?.let { "$it°" } ?: "—")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TemperatureRangeBar(row.lowF, row.highF, weekLowF, weekHighF)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.detailedForecast?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Wind ${row.windLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TemperatureRangeBar(lowF: Int?, highF: Int?, weekLowF: Int, weekHighF: Int) {
    val span = (weekHighF - weekLowF).coerceAtLeast(1).toFloat()
    val low = (lowF ?: highF ?: return).coerceIn(weekLowF, weekHighF)
    val high = (highF ?: lowF ?: return).coerceIn(weekLowF, weekHighF)
    val startFraction = (low - weekLowF) / span
    val widthFraction = ((high - low) / span).coerceAtLeast(MIN_BAR_FRACTION)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .padding(start = maxWidth * startFraction)
                .width(maxWidth * widthFraction)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** The outdoor score, spelled out: what's good about it, and what isn't. */
@Composable
fun OutdoorBreakdownCard(assessment: OutdoorAssessment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Outdoor conditions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                RatingBadge(assessment.rating)
            }
            // The stored score is discomfort; people read a bar left-to-right as "more is better".
            LinearProgressIndicator(
                progress = { (100 - assessment.score) / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = ratingColor(assessment.rating)
            )
            Text(
                "${100 - assessment.score}% comfortable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            assessment.positives.forEach {
                // The same green the Excellent badge uses, so "good" reads as one colour throughout.
                Text(
                    "✓ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = ratingColor(OutdoorRating.EXCELLENT)
                )
            }
            assessment.warnings.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The best daylight windows ahead on weather alone — no task attached, no calendar consulted. */
@Composable
fun BestWindowRow(window: RecommendedWindow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(window.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${window.matchPercent}%",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ratingColor(window.rating)
                )
            }
            window.reasons.take(3).forEach {
                Text(
                    "✓ $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** Section heading shared by every block on the screen. */
@Composable
fun DetailSectionHeader(title: String, note: String? = null) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

/** A day whose high equals its low would otherwise draw a bar of zero width. */
private const val MIN_BAR_FRACTION = 0.06f
