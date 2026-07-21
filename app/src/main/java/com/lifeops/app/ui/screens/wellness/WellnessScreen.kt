@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.wellness

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.WellnessCheckin
import com.lifeops.app.data.model.WellnessKind
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.util.DateUtil
import kotlin.math.abs

@Composable
fun WellnessScreen(
    viewModel: WellnessViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sleepEstimate by viewModel.sleepEstimate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showChooser by remember { mutableStateOf(false) }
    var showCheckIn by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    if (showChooser) {
        LogChooserDialog(
            onCheckIn = { showChooser = false; showCheckIn = true },
            onSleep = { showChooser = false; viewModel.prepareSleepEstimate(); showSleep = true },
            onDismiss = { showChooser = false }
        )
    }
    if (showCheckIn) {
        CheckInDialog(
            onSubmit = { energy, sensory, why ->
                viewModel.logCheckin(energy, sensory, why); showCheckIn = false
            },
            onDismiss = { showCheckIn = false }
        )
    }
    // Wait for the async screen-time estimate before showing the sleep dialog, so its duration
    // field pre-fills with the estimate instead of appearing blank then jumping.
    if (showSleep && sleepEstimate != null) {
        SleepCheckInDialog(
            estimatedMinutes = sleepEstimate?.lastUseMillis?.let {
                com.lifeops.app.util.ScreenTimeEstimator.sleepMinutes(it)
            },
            hasUsageAccess = sleepEstimate?.hasAccess ?: true,
            onGrantAccess = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onSubmit = { energy, tired, mins, why ->
                viewModel.logSleep(energy, tired, mins, why); showSleep = false
            },
            onDismiss = { showSleep = false }
        )
    }

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Log") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { showChooser = true }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { TodayCard(state.today) }
            item { TrendsCard(state) }
            item { WeekCard(state.currentWeek) }

            state.insights?.let { insights ->
                if (insights.hoursEnergyBuckets.size > 1 || insights.aspectEnergy.isNotEmpty()) {
                    item { SectionTitle("Energy insights") }
                    item { InsightsCard(insights) }
                }
            }

            if (state.weeklyTrend.size > 1) {
                item { SectionTitle("Weekly trend") }
                items(state.weeklyTrend.reversed()) { week -> WeekRow(week) }
            }

            if (state.recent.isNotEmpty()) {
                item { SectionTitle("Recent entries") }
                items(state.recent) { entry -> EntryRow(entry) }
            } else {
                item {
                    Text(
                        "No check-ins yet. The app will prompt you through the day and each morning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogChooserDialog(
    onCheckIn: () -> Unit,
    onSleep: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log wellness") },
        text = { Text("Record how you feel right now, or last night's sleep.") },
        confirmButton = { TextButton(onClick = onCheckIn) { Text("Check-in") } },
        dismissButton = { TextButton(onClick = onSleep) { Text("Sleep") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TodayCard(today: DailyWellness?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (today == null) {
                Text(
                    "Nothing logged yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }
            StatLine("Last night's sleep", formatSleep(today.sleepMinutes))
            StatLine("Tired on waking", today.sleepTired?.let { "$it / 10" } ?: "—")
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            StatLine("Check-ins", today.checkinCount.toString())
            StatLine("Avg energy", formatAvg(today.avgEnergy))
            StatLine("Avg sensory load", formatAvg(today.avgSensory))
        }
    }
}

@Composable
private fun WeekCard(week: WeeklyWellness?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (week == null) {
                Text(
                    "No entries this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                return@Column
            }
            StatLine("Avg energy", formatAvg(week.avgEnergy))
            StatLine("Avg sensory load", formatAvg(week.avgSensory))
            StatLine("Avg tiredness", formatAvg(week.avgTired))
            StatLine("Avg sleep", formatSleep(week.avgSleepMinutes?.toInt()))
            StatLine("Check-ins / sleep logs", "${week.checkinCount} / ${week.sleepCount}")
        }
    }
}

@Composable
private fun WeekRow(week: WeeklyWellness) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Week of ${week.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "E ${formatAvg(week.avgEnergy)}  ·  S ${formatAvg(week.avgSensory)}  ·  ${formatSleep(week.avgSleepMinutes?.toInt())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun EntryRow(entry: WellnessCheckin) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (entry.kind == WellnessKind.SLEEP) "Sleep" else "Check-in",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    DateUtil.formatInstant(entry.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            val detail = if (entry.kind == WellnessKind.SLEEP) {
                buildList {
                    entry.sleepMinutes?.let { add("Sleep ${formatSleep(it)}") }
                    entry.tired?.let { add("Tired $it") }
                    entry.energy?.let { add("Energy $it") }
                }.joinToString("  ·  ")
            } else {
                buildList {
                    entry.energy?.let { add("Energy $it") }
                    entry.sensory?.let { add("Sensory $it") }
                }.joinToString("  ·  ")
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
            entry.note?.let {
                Text("\"$it\"", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TrendsCard(state: WellnessReportState) {
    val hasAny = state.energySpark.latest != null ||
        state.sensorySpark.latest != null || state.sleepSpark.latest != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Last 3 weeks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!hasAny) {
                Text("Not enough data yet.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            TrendRow("Energy", state.energySpark, MaterialTheme.colorScheme.primary) { formatAvg(it?.toDouble()) }
            TrendRow("Sensory", state.sensorySpark, MaterialTheme.colorScheme.tertiary) { formatAvg(it?.toDouble()) }
            TrendRow("Sleep", state.sleepSpark, MaterialTheme.colorScheme.secondary) { v ->
                v?.let { String.format("%.1fh", it) } ?: "—"
            }
        }
    }
}

@Composable
private fun TrendRow(label: String, spark: Sparkline, color: Color, fmt: (Float?) -> String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        Sparkline(spark.values, color, Modifier.weight(1f).height(36.dp))
        Spacer(Modifier.width(8.dp))
        Text(fmt(spark.latest), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (pts.isEmpty()) return@Canvas
        if (pts.size == 1) {
            drawCircle(color, radius = 3f, center = Offset(size.width / 2f, size.height / 2f))
            return@Canvas
        }
        val ys = pts.map { it.second }
        val minY = ys.minOrNull() ?: 0f
        val maxY = ys.maxOrNull() ?: 1f
        val spanY = (maxY - minY).takeIf { it > 0f } ?: 1f
        val n = values.size
        val stepX = if (n > 1) size.width / (n - 1) else size.width
        val offsets = pts.map { (i, v) ->
            Offset(i * stepX, size.height - ((v - minY) / spanY) * size.height)
        }
        for (k in 0 until offsets.size - 1) {
            drawLine(color, offsets[k], offsets[k + 1], strokeWidth = 3f)
        }
    }
}

@Composable
private fun InsightsCard(insights: WellnessInsights) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            insights.hoursEnergyCorr?.let { r ->
                Text(hoursEnergyInterpretation(r), style = MaterialTheme.typography.bodyMedium)
            }
            if (insights.hoursEnergyBuckets.isNotEmpty()) {
                Text("Energy by hours logged that day", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                insights.hoursEnergyBuckets.forEach { BucketBar(it) }
            }
            if (insights.aspectEnergy.isNotEmpty()) {
                HorizontalDivider()
                Text("Energy by aspect worked", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                insights.overallAvgEnergy?.let {
                    Text("Overall daily average: ${formatAvg(it)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                insights.aspectEnergy.forEach { AspectEnergyRowView(it) }
            }
            Text(
                "Based on ${insights.dayCount} day${if (insights.dayCount == 1) "" else "s"} with an energy reading. " +
                    "Associations, not proof.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun BucketBar(bucket: HoursEnergyBucket) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(bucket.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((bucket.avgEnergy / 10.0).toFloat().coerceIn(0f, 1f))
                    .height(16.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatAvg(bucket.avgEnergy)} (${bucket.dayCount})",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp)
        )
    }
}

@Composable
private fun AspectEnergyRowView(row: AspectEnergyRow) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(parseColor(row.color))
        )
        Spacer(Modifier.width(8.dp))
        Text(row.aspectName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            formatAvg(row.avgEnergy),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(8.dp))
        val deltaColor = when {
            row.delta > 0.05 -> Color(0xFF2E7D32)
            row.delta < -0.05 -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
        Text(
            (if (row.delta >= 0) "+" else "−") + String.format("%.1f", abs(row.delta)),
            style = MaterialTheme.typography.bodySmall,
            color = deltaColor,
            modifier = Modifier.width(40.dp)
        )
        Text(
            "${row.dayCount}d",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(28.dp)
        )
    }
}

private fun hoursEnergyInterpretation(r: Double): String {
    val strength = when {
        abs(r) < 0.15 -> "little to no"
        abs(r) < 0.35 -> "a weak"
        abs(r) < 0.6 -> "a moderate"
        else -> "a strong"
    }
    val direction = if (r >= 0) "more hours logged tends to go with higher energy"
    else "more hours logged tends to go with lower energy"
    return if (abs(r) < 0.15) "There's little link between hours logged and energy so far."
    else "There's $strength link between hours logged and energy — $direction."
}

private fun formatAvg(v: Double?): String = v?.let { String.format("%.1f", it) } ?: "—"

private fun formatSleep(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "—"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}
