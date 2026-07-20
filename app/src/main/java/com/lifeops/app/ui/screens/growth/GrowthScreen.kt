@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.growth

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lifeops.app.util.GrowthBars
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.ui.theme.parseColor
import com.lifeops.app.util.GrowthRings
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun GrowthScreen(viewModel: GrowthViewModel, onBack: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppHeader(navigationIcon = { onBack?.let { BackNavIcon(it) } }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GrowthControls(
                state = state,
                onColorByHours = viewModel::setColorByHours,
                onGlow = viewModel::setGlow,
                onView = viewModel::setView,
                onHelp = { showHelp = true }
            )

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.scene == null || state.totalWeeks == 0 -> EmptyGrowthState()
                else -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (state.view) {
                            GrowthView.RINGS -> RingCanvas(
                                scene = state.scene!!,
                                selectedWeekId = state.selectedWeekId,
                                onSelectWeek = viewModel::selectWeek
                            )
                            GrowthView.BARS -> BarChart(
                                chart = state.chart!!,
                                selectedWeekId = state.selectedWeekId,
                                onSelectWeek = viewModel::selectWeek
                            )
                        }
                    }
                    SelectedWeekDetail(state)
                    Legend(state.legend, state.latestWeekLabel)
                }
            }
        }

        if (showHelp) RingsHelpDialog(onDismiss = { showHelp = false })
    }
}

@Composable
private fun GrowthControls(
    state: GrowthUiState,
    onColorByHours: (Boolean) -> Unit,
    onGlow: (Boolean) -> Unit,
    onView: (GrowthView) -> Unit,
    onHelp: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    append("${state.totalWeeks} week${if (state.totalWeeks == 1) "" else "s"}")
                    val h = state.totalHours
                    if (h > 0) append(" · ${if (h % 1.0 == 0.0) h.toInt().toString() else String.format("%.1f", h)}h logged")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = state.view == GrowthView.RINGS,
                    onClick = { onView(GrowthView.RINGS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Rings") }
                SegmentedButton(
                    selected = state.view == GrowthView.BARS,
                    onClick = { onView(GrowthView.BARS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Bars") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.colorByHours,
                onClick = { onColorByHours(!state.colorByHours) },
                label = { Text("Colour by hours") }
            )
            if (state.view == GrowthView.RINGS) {
                FilterChip(
                    selected = state.glowEnabled,
                    enabled = state.colorByHours,
                    onClick = { onGlow(!state.glowEnabled) },
                    label = { Text("Glow") }
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp) {
                Icon(Icons.Default.Info, contentDescription = "How the Growth Record works")
            }
        }
    }
}

@Composable
private fun RingsHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How the Growth Record works", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HelpLine("Each week becomes one ring, drawn once and never changed. Newer weeks circle further out.")
                HelpLine("To grow a ring: log time against your tasks, then close the week on This Week. More hours → thicker, brighter bands (≈50h reads as \"full\", then it glows).")
                HelpLine("Every aspect keeps the same track across all rings, so you can follow one outward through time.")
                HelpLine("A week with zero logged hours leaves a permanent grey scar — never a gap.")
                HelpLine("Prefer a timeline? Switch to \"Bars\": the same weeks as stacked bars, oldest on the left, taller = more hours. Tap a bar (or a ring) for that week's hours.")
                HelpLine("\"Colour by hours\" and \"Glow\" only change how it looks, never the record.")
                HelpLine("History is sealed at week-close: deleting, renaming or recolouring an aspect later won't repaint past rings. It can't be faked or ground.")
                HelpLine("Pinch or use the slider to zoom, drag to pan, Fit to re-centre. Tap a ring for that week's hours. Export from Settings → Data.")
            }
        }
    )
}

@Composable
private fun HelpLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Concentric-ring renderer. The geometry never changes — only this view's scale/offset do.
 * Auto-fit shrinks long records to stay on screen; pinch + drag (and the Fit button) take
 * over on first interaction without a jump, because effectiveScale = userZoom * fitScale and
 * userZoom starts at 1 (== the auto-fit).
 */
@Composable
private fun RingCanvas(
    scene: GrowthRings.Scene,
    selectedWeekId: String?,
    onSelectWeek: (String?) -> Unit
) {
    var userZoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val contentRadius = scene.contentRadius.toFloat().coerceAtLeast(1f)
    val bgCenter = MaterialTheme.colorScheme.surfaceVariant
    val bgEdge = MaterialTheme.colorScheme.background
    val seedColor = MaterialTheme.colorScheme.onSurface
    val selectionColor = MaterialTheme.colorScheme.primary
    val marginPx = with(LocalDensity.current) { 16.dp.toPx() }

    fun fitScale(w: Float, h: Float): Float {
        val viewR = min(w, h) / 2f
        return min(1f, (viewR - marginPx) / contentRadius).coerceIn(0.05f, 1f)
    }

    Column(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        userZoom = (userZoom * zoomChange).coerceIn(0.3f, 6f)
                        pan += panChange
                    }
                }
                .pointerInput(scene) {
                    detectTapGestures { offset ->
                        val w = canvasSize.width.toFloat()
                        val h = canvasSize.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectTapGestures
                        val eff = userZoom * fitScale(w, h)
                        val cx = w / 2f + pan.x
                        val cy = h / 2f + pan.y
                        val localR = hypot(offset.x - cx, offset.y - cy) / eff
                        val ring = scene.rings.firstOrNull {
                            localR >= it.innerR.toFloat() && localR <= it.outerR.toFloat()
                        }
                        onSelectWeek(ring?.weekId)
                    }
                }
        ) {
            val eff = userZoom * fitScale(size.width, size.height)
            val center = Offset(size.width / 2f + pan.x, size.height / 2f + pan.y)

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(bgCenter, bgEdge),
                    center = center,
                    radius = (contentRadius * eff).coerceAtLeast(1f)
                )
            )

            // Glow layer first, entirely behind the crisp bands (never bleeds over them).
            val glows = scene.glowBands()
            if (glows.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                    }
                    glows.forEach { b ->
                        val mid = ((b.innerR + b.outerR) / 2.0).toFloat() * eff
                        val width = (b.outerR - b.innerR).toFloat() * eff
                        val blur = GrowthRings.GLOW_STDDEV.toFloat() * eff
                        if (width <= 0f || blur <= 0f) return@forEach
                        paint.strokeWidth = width
                        paint.color = parseColor(b.colorHex).copy(alpha = b.alpha.toFloat()).toArgb()
                        paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                        canvas.nativeCanvas.drawCircle(center.x, center.y, mid, paint)
                    }
                }
            }

            // Crisp bands (and grey scars).
            scene.fillBands().forEach { b ->
                val mid = ((b.innerR + b.outerR) / 2.0).toFloat() * eff
                val width = (b.outerR - b.innerR).toFloat() * eff
                if (width <= 0f) return@forEach
                drawCircle(
                    color = parseColor(b.colorHex).copy(alpha = b.alpha.toFloat()),
                    radius = mid,
                    center = center,
                    style = Stroke(width = width)
                )
            }

            // Selection highlight: a thin ring around the tapped week.
            selectedWeekId?.let { id ->
                scene.rings.firstOrNull { it.weekId == id }?.let { ring ->
                    drawCircle(
                        color = selectionColor,
                        radius = ring.outerR.toFloat() * eff,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Seed dot at the centre.
            drawCircle(color = seedColor.copy(alpha = 0.85f), radius = scene.seedR.toFloat() * eff, center = center)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Zoom", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = userZoom,
                onValueChange = { userZoom = it },
                valueRange = 0.3f..6f,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { userZoom = 1f; pan = Offset.Zero }) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Fit")
            }
        }
    }
}

/**
 * Stacked-bar timeline over the same sealed record the rings draw: one bar per week, oldest
 * on the left, aspects stacked bottom-to-top in the stable order, height = hours. A zero-hour
 * week is a short grey scar stub — never a gap. Tapping a bar selects that week (toggles off on
 * a second tap), feeding the same [SelectedWeekDetail] panel the rings use.
 */
@Composable
private fun BarChart(
    chart: GrowthBars.Chart,
    selectedWeekId: String?,
    onSelectWeek: (String?) -> Unit
) {
    val bars = chart.bars
    val listState = rememberLazyListState()
    // Land on the newest week; growth reads left-to-right, so the latest is the far right.
    LaunchedEffect(bars.size) {
        if (bars.isNotEmpty()) listState.scrollToItem(bars.size - 1)
    }
    val step = maxOf(1, (bars.size + 7) / 8)   // ~8 x-axis labels, evenly spaced

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val labelHeight = 24.dp
        val plotHeight = (maxHeight - labelHeight - 6.dp).coerceAtLeast(48.dp)
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            itemsIndexed(bars, key = { _, b -> b.weekId }) { index, bar ->
                BarColumn(
                    bar = bar,
                    maxHours = chart.maxHours,
                    plotHeight = plotHeight,
                    labelHeight = labelHeight,
                    showLabel = index % step == 0 || index == bars.lastIndex,
                    selected = bar.weekId == selectedWeekId,
                    onClick = { onSelectWeek(if (selectedWeekId == bar.weekId) null else bar.weekId) }
                )
            }
        }
    }
}

@Composable
private fun BarColumn(
    bar: GrowthBars.Bar,
    maxHours: Double,
    plotHeight: androidx.compose.ui.unit.Dp,
    labelHeight: androidx.compose.ui.unit.Dp,
    showLabel: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val barWidth = 24.dp
    val barShape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.height(plotHeight).width(barWidth),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .width(barWidth)
                    .clip(barShape)
                    .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, barShape) else Modifier),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (bar.isScar) {
                    Box(
                        Modifier.width(barWidth).height(6.dp)
                            .background(parseColor(GrowthBars.SCAR_COLOR).copy(alpha = 0.7f))
                    )
                } else {
                    // segments are bottom-to-top; a top-down Column draws them in reverse.
                    bar.segments.asReversed().forEach { seg ->
                        val h = plotHeight * (seg.hours / maxHours).toFloat()
                        Box(Modifier.width(barWidth).height(h).background(parseColor(seg.colorHex)))
                    }
                }
            }
        }
        Box(
            modifier = Modifier.width(barWidth + 8.dp).height(labelHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            if (showLabel) {
                Text(
                    shortDate(bar.label),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** "2026-01-05" -> "1/5"; falls back to the raw label if it isn't an ISO date. */
private fun shortDate(label: String): String {
    val parts = label.split("-")
    if (parts.size != 3) return label
    val month = parts[1].toIntOrNull() ?: return label
    val day = parts[2].toIntOrNull() ?: return label
    return "$month/$day"
}

@Composable
private fun SelectedWeekDetail(state: GrowthUiState) {
    val weekId = state.selectedWeekId ?: return
    val ring = state.scene?.rings?.firstOrNull { it.weekId == weekId } ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (ring.isScar) "Week of ${ring.label} — a scar (no hours logged)"
                else "Week of ${ring.label}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (state.selectedWeekDetail.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                state.selectedWeekDetail.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Surface(Modifier.size(10.dp), shape = MaterialTheme.shapes.extraSmall, color = parseColor(row.swatchHex)) {}
                        Spacer(Modifier.width(8.dp))
                        Text(row.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(formatHours(row.hours), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(rows: List<GrowthLegendRow>, latestWeekLabel: String?) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            latestWeekLabel?.let { "Latest week · $it" } ?: "Latest week",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.aspectId }) { row ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(Modifier.size(12.dp), shape = MaterialTheme.shapes.extraSmall, color = parseColor(row.swatchHex)) {}
                        Spacer(Modifier.width(6.dp))
                        Text(
                            row.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.hours > 0) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(formatHours(row.hours), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGrowthState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No rings yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Log time against your tasks and close a week. Each week becomes one permanent ring — thicker effort, brighter colour. Skipped weeks leave a grey scar. Nothing here can be faked or ground.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatHours(h: Double): String =
    if (h % 1.0 == 0.0) "${h.toInt()}h" else String.format("%.1fh", h)
