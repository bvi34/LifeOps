@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.data.model.Aspect
import com.lifeops.app.data.model.CarryForwardEntry
import com.lifeops.app.data.model.CarryoverSummaryRow
import com.lifeops.app.data.model.CostUsageRow
import com.lifeops.app.data.model.PriorityCompletionRow
import com.lifeops.app.data.model.ProjectStats
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.data.model.ResourceResetCycle
import com.lifeops.app.data.model.ScoringPoint
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.components.formatMinutes
import com.lifeops.app.ui.theme.CompletedGreen
import com.lifeops.app.ui.theme.ExpiredRed
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.ui.theme.priorityColor

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onNavigateToProject: (String) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            RangeSelector(
                selected = state.range,
                onSelect = viewModel::setRange
            )
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { StatsSummaryRow(state) }
                    item { CompletionTrendChart(state.completionTrend) }
                    item { AspectBalanceChart(state.aspectResourceShares) }
                    if (state.timeByAspect.isNotEmpty()) {
                        item { TimeByAspectCard(state.timeByAspect, state.totalTimeMinutes) }
                    }
                    if (state.scoringTrend.isNotEmpty()) {
                        item { ScoringTrendCard(state.scoringTrend) }
                    }
                    if (state.projectStats.isNotEmpty()) {
                        item { ProjectStatsCard(state.projectStats, state.aspects, onNavigateToProject) }
                    }
                    if (state.priorityBreakdown.isNotEmpty()) {
                        item { PriorityBreakdownCard(state.priorityBreakdown) }
                    }
                    if (state.costUsage.isNotEmpty()) {
                        item { CostUsageCard(state.costUsage) }
                    }
                    if (state.categorySlipRates.isNotEmpty()) {
                        item {
                            Text(
                                "Category Slip Rates",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(state.categorySlipRates) { cat ->
                            CategorySlipRow(cat)
                        }
                    }
                    if (state.selfRatingPoints.isNotEmpty()) {
                        item { SelfRatingCard(state.selfRatingPoints, state.avgSelfRating) }
                    }
                    state.wellnessSummary?.let { item { WellnessSummaryCard(it) } }
                    state.nutritionSummary?.let { item { NutritionSummaryCard(it) } }
                    if (state.counterTotals.isNotEmpty()) {
                        item { CountersSummaryCard(state.counterTotals) }
                    }
                    state.readingSummary?.let { item { ReadingSummaryCard(it) } }
                    if (state.carryHistory.isNotEmpty() || state.carryoverSummary.isNotEmpty()) {
                        item { CarryoverCard(state.carryHistory, state.carryoverSummary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeSelector(selected: ReportRange, onSelect: (ReportRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ReportRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(range.label) }
            )
        }
    }
}

@Composable
private fun StatsSummaryRow(state: ReportsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatBox("HD Hit Rate", "${(state.hardDeadlineHitRate * 100).toInt()}%")
        StatBox("Carry Forward", "${(state.carryForwardRate * 100).toInt()}%")
        StatBox("Weeks Tracked", state.snapshots.size.toString())
        if (state.totalTimeMinutes > 0) {
            val h = state.totalTimeMinutes / 60
            val m = state.totalTimeMinutes % 60
            StatBox("Time", if (h > 0) "${h}h ${m}m" else "${m}m")
        }
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun CompletionTrendChart(trend: List<WeeklyCompletionPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Completion Rate (week over week)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (trend.isEmpty()) {
                Text("No data yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    val w = size.width
                    val h = size.height
                    val step = if (trend.size > 1) w / (trend.size - 1) else w
                    val points = trend.mapIndexed { i, pt ->
                        Offset(i * step, h - pt.rate * h)
                    }
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.3f),
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 1.dp.toPx()
                    )
                    for (i in 0 until points.size - 1) {
                        drawLine(
                            color = primaryColor,
                            start = points[i],
                            end = points[i + 1],
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    points.forEach { pt ->
                        drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = pt)
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectBalanceChart(shares: List<AspectResourceShare>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Aspect Balance", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (shares.isEmpty()) {
                Text("No data yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                Canvas(modifier = Modifier.size(160.dp).align(Alignment.CenterHorizontally)) {
                    var startAngle = -90f
                    shares.forEach { share ->
                        val sweep = share.share * 360f
                        drawArc(
                            color = parseColor(share.color),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = true,
                            size = Size(size.width, size.height)
                        )
                        startAngle += sweep
                    }
                }
                Spacer(Modifier.height(8.dp))
                shares.forEach { share ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Surface(modifier = Modifier.size(10.dp), color = parseColor(share.color), shape = MaterialTheme.shapes.extraSmall) {}
                        Spacer(Modifier.width(8.dp))
                        Text(share.aspectName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("${(share.share * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeByAspectCard(rows: List<AspectTimeRow>, totalMinutes: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val totalLabel = if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes}m"
            Text(
                "Time Spent — $totalLabel total",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(modifier = Modifier.size(10.dp), color = parseColor(row.color), shape = MaterialTheme.shapes.extraSmall) {}
                    Spacer(Modifier.width(8.dp))
                    Text(row.aspectName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    val h = row.totalMinutes / 60
                    val m = row.totalMinutes % 60
                    Text(
                        if (h > 0) "${h}h ${m}m" else "${m}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (totalMinutes > 0) {
                    LinearProgressIndicator(
                        progress = { row.totalMinutes.toFloat() / totalMinutes },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                        color = parseColor(row.color)
                    )
                }
            }
        }
    }
}

@Composable
private fun CarryoverCard(
    history: List<CarryForwardEntry>,
    summary: List<CarryoverSummaryRow>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (summary.isNotEmpty()) {
                Text("Carryover Task Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                summary.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.taskTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            val statusText = if (row.isStillOpen)
                                "still open · deferred ${row.carriedCount} week${if (row.carriedCount != 1) "s" else ""}"
                            else
                                "completed week of ${row.completionWeekLabel} · deferred ${row.carriedCount}×"
                            val originText = if (row.originWeekLabel.isNotEmpty()) " · from ${row.originWeekLabel}" else ""
                            val timeText = if (row.lineageMinutes > 0) " · ${formatMinutes(row.lineageMinutes)} total" else ""
                            Text(
                                "$statusText$originText$timeText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            if (!row.isStillOpen && row.pointsEarned > 0) {
                                Text(
                                    "+${row.pointsEarned} pts",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CompletedGreen
                                )
                            }
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (row.isStillOpen)
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            else CompletedGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "↩${row.carriedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.isStillOpen) MaterialTheme.colorScheme.tertiary else CompletedGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (history.isNotEmpty()) {
                Text("Carry-Forward History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                history.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.taskTitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            val timesText = if (entry.carriedCount == 1) "carried 1 time"
                                            else "carried ${entry.carriedCount} times"
                            val statusText = when (entry.finalStatus) {
                                TaskStatus.COMPLETED -> "completed week of ${entry.weekLabel}"
                                TaskStatus.EXPIRED -> "expired week of ${entry.weekLabel}"
                                TaskStatus.INCOMPLETE -> "incomplete week of ${entry.weekLabel}"
                                TaskStatus.SKIPPED -> "skipped week of ${entry.weekLabel}"
                                TaskStatus.PENDING -> "still pending"
                                else -> "week of ${entry.weekLabel}"
                            }
                            Text(
                                "$timesText · $statusText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        val badgeColor = when (entry.finalStatus) {
                            TaskStatus.COMPLETED -> CompletedGreen
                            TaskStatus.EXPIRED -> ExpiredRed
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                entry.carriedCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun CategorySlipRow(cat: CategorySlipRate) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(cat.categoryName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { cat.rate },
            modifier = Modifier.width(120.dp),
            color = if (cat.rate > 0.5f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text("${(cat.rate * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun ScoringTrendCard(trend: List<ScoringPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Scoring Trend (Resources/Week)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (trend.isEmpty()) {
                Text("No data yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                val primaryColor = MaterialTheme.colorScheme.primary
                val maxVal = trend.maxOf { it.resourcesEarned }.takeIf { it > 0 } ?: 1
                Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    val w = size.width
                    val h = size.height
                    val step = if (trend.size > 1) w / (trend.size - 1) else w
                    val points = trend.mapIndexed { i, pt ->
                        Offset(i * step, h - (pt.resourcesEarned.toFloat() / maxVal) * h)
                    }
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.3f),
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 1.dp.toPx()
                    )
                    for (i in 0 until points.size - 1) {
                        drawLine(
                            color = primaryColor,
                            start = points[i],
                            end = points[i + 1],
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    points.forEach { pt ->
                        drawCircle(color = primaryColor, radius = 4.dp.toPx(), center = pt)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectStatsCard(
    projectStats: List<ProjectStats>,
    aspects: Map<String, Aspect>,
    onProjectClick: (String) -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Project Health", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            projectStats.forEach { stat ->
                val isDone = stat.project.status == ProjectStatus.COMPLETED
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .then(Modifier.clickable { onProjectClick(stat.project.id) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stat.project.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (isDone) MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    if (isDone) "Done" else "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDone) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        stat.project.aspectId?.let { aspectId ->
                            aspects[aspectId]?.let { aspect ->
                                Text(
                                    aspect.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                        if (stat.taskCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { stat.completedCount.toFloat() / stat.taskCount },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (isDone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${stat.completedCount}/${stat.taskCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (stat.totalTimeMinutes > 0) {
                            val h = stat.totalTimeMinutes / 60
                            val m = stat.totalTimeMinutes % 60
                            Text(
                                if (h > 0) "${h}h ${m}m" else "${m}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun PriorityBreakdownCard(rows: List<PriorityCompletionRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Completion by Priority", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                val color = priorityColor(row.priority.label)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        row.priority.label.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.width(64.dp)
                    )
                    LinearProgressIndicator(
                        progress = { row.rate },
                        modifier = Modifier.weight(1f),
                        color = color
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${(row.rate * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.width(36.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CostUsageCard(rows: List<CostUsageRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Resource Usage",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.resourceName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        val cycleLabel = when (row.resetCycle) {
                            ResourceResetCycle.WEEKLY -> "resets weekly"
                            ResourceResetCycle.MONTHLY -> "resets monthly"
                            ResourceResetCycle.NEVER -> "no reset"
                        }
                        Text(cycleLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            row.totalAmount.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        row.capacity?.let { cap ->
                            Text(
                                "of $cap",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                if (row.capacity != null && row.capacity > 0) {
                    LinearProgressIndicator(
                        progress = { (row.totalAmount.toFloat() / row.capacity).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        color = if (row.totalAmount >= row.capacity) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                    )
                } else {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SelfRatingCard(points: List<SelfRatingPoint>, avg: Float?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Weekly Self-Rating",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                avg?.let {
                    Text(
                        "avg ${String.format("%.1f", it)}/10",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            points.takeLast(8).forEach { point ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        point.weekLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(80.dp)
                    )
                    LinearProgressIndicator(
                        progress = { point.rating / 10f },
                        modifier = Modifier.weight(1f),
                        color = when {
                            point.rating >= 7 -> CompletedGreen
                            point.rating >= 4 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${point.rating}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.width(20.dp)
                    )
                }
                point.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 80.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

// --- New-domain report cards (wellness / nutrition / counters / reading) ----------------------
// Summary-only: these surface the newer LifeOps domains in Reports without feeding the resource
// economy. Each is emitted only when it has data for the selected range (see ReportsScreen).

private fun fmtAvg(value: Float?): String = value?.let { "%.1f".format(it) } ?: "—"

@Composable
private fun WellnessSummaryCard(summary: WellnessSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Wellness", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "${summary.checkinCount} check-in${if (summary.checkinCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox("Avg energy", fmtAvg(summary.avgEnergy))
                StatBox("Avg sensory", fmtAvg(summary.avgSensory))
                // -1 (no) → +1 (yes), so it reads as a signed value rather than a 1-10 average.
                StatBox("Initiative", summary.avgInitiative?.let { "%+.1f".format(it) } ?: "—")
                StatBox("Avg sleep", summary.avgSleepMinutes?.let { formatMinutes(it) } ?: "—")
            }
        }
    }
}

@Composable
private fun NutritionSummaryCard(summary: NutritionSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Nutrition", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Per-day average over ${summary.daysLogged} logged day${if (summary.daysLogged == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox("Calories", summary.avgCalories.toString())
                StatBox("Carbs", "${summary.avgCarbsG}g")
                StatBox("Protein", "${summary.avgProteinG}g")
                StatBox("Fat", "${summary.avgFatG}g")
            }
        }
    }
}

@Composable
private fun CountersSummaryCard(rows: List<CounterTotalRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Counters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        row.total.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingSummaryCard(summary: ReadingSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reading", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox("Time", formatMinutes(summary.totalMinutes))
                StatBox("Sessions", summary.sessions.toString())
                StatBox("Finished", summary.booksFinished.toString())
                StatBox("Points", summary.points.toString())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Earned at ${summary.pointsPerHour} pts/hour of engaged reading",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
