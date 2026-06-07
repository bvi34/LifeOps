package com.lifeops.app.ui.screens.reports

import androidx.compose.foundation.Canvas
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
import com.lifeops.app.ui.theme.parseColor

@Composable
fun ReportsScreen(viewModel: ReportsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Reports") }) }
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
                    if (state.categorySlipRates.isNotEmpty()) {
                        item { Text("Category Slip Rates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                        items(state.categorySlipRates) { cat ->
                            CategorySlipRow(cat)
                        }
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
