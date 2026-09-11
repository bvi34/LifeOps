package com.health.app.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.logic.DoseSchedule
import com.health.app.logic.Fever
import com.health.app.logic.HealthWhen
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.logic.Weight
import com.health.app.logic.WeightUnit
import com.health.app.ui.common.*
import com.operations.suite.ui.fields.SuiteNumberField
import com.operations.suite.ui.pickers.SuiteWhenField

/**
 * The measurement history for one person: the temperature curve first, because it is the one people
 * come here to read, then every reading of every kind in one list.
 */
@Composable
fun VitalsScreen(vm: VitalsViewModel, onOpenPeople: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val readings by vm.readings.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()

    var showTemp by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf<Reading?>(null) }
    var chartType by remember { mutableStateOf(ReadingType.TEMPERATURE) }

    val snackbar = remember { SnackbarHostState() }
    UndoHost(vm.undoOffers, snackbar, vm::undo)

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    // Anything with two of the same kind can be drawn; one reading is a dot, not a trend. Temperature
    // leads when it is there, because it is the one people come to this tab to read.
    val chartable = remember(readings) {
        ReadingType.entries.filter { type -> readings.count { it.type == type } >= 2 }
    }
    val charted = remember(readings, chartable, chartType) {
        val type = chartType.takeIf { it in chartable } ?: chartable.firstOrNull()
        type?.let { ChartedReadings(it, readings.filter { r -> r.type == it }.sortedBy { r -> r.takenAt }) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTemp = true },
                text = { Text("Temperature") },
                icon = {}
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ProfileBar(profiles, selected?.id, vm::select, onOpenPeople)
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "chart") {
                    SectionCard(title = charted?.type?.label ?: "Temperature") {
                        if (charted == null) {
                            Text(
                                "Two readings of the same kind draw a line, and nothing has two yet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            // The picker appears only once there is a choice to make: a household
                            // that only takes temperatures should not be asked which chart it wants.
                            if (chartable.size > 1) {
                                ChoiceRow(
                                    options = chartable,
                                    selected = charted.type,
                                    onSelect = { chartType = it },
                                    label = { it.label }
                                )
                            }
                            ReadingChart(
                                readings = charted.readings,
                                threshold = charted.threshold,
                                isFlagged = charted.isFlagged,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    formatStamp(charted.readings.first().takenAt),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(charted.span(unit, weightUnit), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    formatStamp(charted.readings.last().takenAt),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(charted.caption(unit), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item(key = "other") {
                    SectionCard(
                        title = "Other measurements",
                        trailing = { TextButton(onClick = { showOther = true }) { Text("Record") } }
                    ) {
                        Text(
                            "Heart rate, breathing, oxygen, blood pressure and weight — recorded the " +
                                "same way, and kept with the same illness.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (readings.isEmpty()) {
                    item(key = "empty") {
                        Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    item(key = "history-header") {
                        Text("History", style = MaterialTheme.typography.titleSmall)
                    }
                    items(readings, key = { it.id }) { reading ->
                        ReadingRow(
                            reading = reading,
                            unit = unit,
                            weightUnit = weightUnit,
                            onClick = { correcting = reading },
                            onDelete = { vm.delete(reading) }
                        )
                    }
                }
            }
        }
    }

    val profile = selected
    if (showTemp && profile != null) {
        LogTemperatureDialog(
            unit = unit,
            ageMonths = profile.ageMonthsAt(System.currentTimeMillis()),
            onDismiss = { showTemp = false },
            onConfirm = { celsius, site, note, at ->
                vm.logTemperature(celsius, site, note, at)
                showTemp = false
            }
        )
    }
    // Correcting is the same form as recording, filled in — see the dialogs' own notes for why.
    correcting?.let { reading ->
        if (reading.type == ReadingType.TEMPERATURE) {
            LogTemperatureDialog(
                unit = unit,
                ageMonths = profile?.ageMonthsAt(System.currentTimeMillis()),
                editing = reading,
                onDismiss = { correcting = null },
                onConfirm = { celsius, site, note, at ->
                    vm.update(reading.copy(value = celsius, site = site, note = note, takenAt = at))
                    correcting = null
                }
            )
        } else {
            LogOtherReadingDialog(
                weightUnit = weightUnit,
                editing = reading,
                onDismiss = { correcting = null },
                onConfirm = { _, value, secondary, note, at ->
                    vm.update(
                        reading.copy(
                            value = value,
                            secondaryValue = secondary,
                            note = note,
                            takenAt = at
                        )
                    )
                    correcting = null
                }
            )
        }
    }

    if (showOther) {
        LogOtherReadingDialog(
            weightUnit = weightUnit,
            onDismiss = { showOther = false },
            onConfirm = { type, value, secondary, note, at ->
                vm.logOther(type, value, secondary, note, at)
                showOther = false
            }
        )
    }
}

@Composable
private fun ReadingRow(
    reading: Reading,
    unit: TempUnit,
    weightUnit: WeightUnit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val value = formatReading(reading, unit, weightUnit)
    val assessment = if (reading.type == ReadingType.TEMPERATURE) {
        Fever.assess(reading.value, reading.site ?: TempSite.ORAL)
    } else {
        null
    }
    RecordRow(
        headline = "${reading.type.label} · $value",
        support = listOfNotNull(
            formatStamp(reading.takenAt),
            reading.site?.label,
            assessment?.band?.label,
            reading.note
        ).joinToString(" · "),
        trailingColor = assessment?.let { careColor(it.careLevel) },
        onClick = onClick,
        onDelete = onDelete
    )
}

/**
 * One measurement's readings, ready to draw: the series, the line that matters for it if there is
 * one, and which points to call out.
 *
 * A small type rather than four parallel values on the screen, because "which readings, which
 * threshold, which points are flagged" is one decision made per measurement and getting two of the
 * three from temperature and the fourth from something else is how a chart lies.
 */
private data class ChartedReadings(
    val type: ReadingType,
    /** Ascending by time. Two or more, or there is no line to draw. */
    val readings: List<Reading>
) {
    /** The fever line, on the one chart that has a published threshold to draw. */
    val threshold: Double? get() = if (type == ReadingType.TEMPERATURE) Fever.FEVER_C else null

    /**
     * Which points to draw in the alarm colour.
     *
     * Only temperature has an answer Health is qualified to give — `Fever` is a judgement it makes
     * from published thresholds. Nothing else is flagged, because colouring an oxygen saturation red
     * would be Health inventing a clinical opinion it does not have. See `logic/Vitals`.
     */
    val isFlagged: (Reading) -> Boolean
        get() = when (type) {
            ReadingType.TEMPERATURE -> { reading ->
                reading.value + (reading.site ?: TempSite.ORAL).toOralOffsetC >= Fever.FEVER_C
            }
            else -> { _ -> false }
        }

    /**
     * "36.4 °C – 39.1 °C" — the range the chart is drawn over, which for a blood pressure spans both
     * of its numbers, because both are on the chart.
     */
    fun span(unit: TempUnit, weightUnit: WeightUnit): String {
        val values = readings.map { it.value } + readings.mapNotNull { it.secondaryValue }
        val low = formatValue(type, values.min(), unit, weightUnit)
        val high = formatValue(type, values.max(), unit, weightUnit)
        return "$low – $high"
    }

    fun caption(unit: TempUnit): String {
        val span = readings.last().takenAt - readings.first().takenAt
        val over = "${readings.size} readings over ${DoseSchedule.formatDuration(span)}"
        return when (type) {
            ReadingType.TEMPERATURE ->
                "$over · the dashed line is ${Temperature.format(Fever.FEVER_C, unit)}, where a fever starts."
            ReadingType.BLOOD_PRESSURE ->
                "$over · two lines, because a systolic on its own is not a blood pressure — the upper " +
                    "one is it, the lower the diastolic."
            else -> "$over · plotted against real time, so a gap looks like a gap."
        }
    }
}

/**
 * The curve. Deliberately plain: a line, a dot per reading, and — where there is one worth drawing —
 * a single dashed rule, because the only question a chart like this is asked is "is it above the
 * line, and is it going up or down". Points are plotted against real time, not reading number, so a
 * gap in the night looks like a gap.
 *
 * It used to draw temperatures only, which left the household tracking a weight or a blood pressure
 * for a long-running condition reading a list of numbers and doing the trend in their head — the
 * one job a chart is for. The same line now draws any measurement; only temperature brings a
 * threshold and flagged points with it, because it is the only one Health has a published rule for.
 *
 * A blood pressure draws **both** of its numbers, because a systolic alone is not a blood pressure.
 */
@Composable
private fun ReadingChart(
    readings: List<Reading>,
    threshold: Double?,
    isFlagged: (Reading) -> Boolean,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val flagColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val secondColor = MaterialTheme.colorScheme.tertiary

    val values = readings.map { it.value } + readings.mapNotNull { it.secondaryValue }
    val hasSecond = readings.any { it.secondaryValue != null }
    // The threshold is inside the visible range whenever there is one, so "below the line" is
    // visibly below it rather than off the top of a chart that happens to have no high readings.
    val low = minOf(values.min(), threshold ?: values.min())
    val high = maxOf(values.max(), threshold ?: values.max())
    // A tenth of the range as breathing room, and never less than half a unit — a flat series would
    // otherwise be drawn on a range of zero and divide by it.
    val pad = ((high - low) * 0.1).coerceAtLeast(0.5)
    val minValue = low - pad
    val maxValue = high + pad
    val firstAt = readings.first().takenAt
    val span = (readings.last().takenAt - firstAt).coerceAtLeast(1L)

    Canvas(modifier = modifier) {
        fun x(at: Long) = ((at - firstAt).toFloat() / span.toFloat()) * size.width
        fun y(value: Double) =
            size.height - (((value - minValue) / (maxValue - minValue)).toFloat() * size.height)

        threshold?.let { line ->
            val lineY = y(line)
            drawLine(
                color = flagColor,
                start = Offset(0f, lineY),
                end = Offset(size.width, lineY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            )
        }
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f
        )

        fun path(value: (Reading) -> Double?) = Path().apply {
            var started = false
            readings.forEach { reading ->
                val v = value(reading) ?: return@forEach
                val point = Offset(x(reading.takenAt), y(v))
                if (started) lineTo(point.x, point.y) else moveTo(point.x, point.y).also { started = true }
            }
        }

        drawPath(path { it.value }, color = lineColor, style = Stroke(width = 4f))
        if (hasSecond) {
            drawPath(path { it.secondaryValue }, color = secondColor, style = Stroke(width = 4f))
        }

        readings.forEach { reading ->
            drawCircle(
                color = if (isFlagged(reading)) flagColor else lineColor,
                radius = 6f,
                center = Offset(x(reading.takenAt), y(reading.value))
            )
            reading.secondaryValue?.let {
                drawCircle(color = secondColor, radius = 6f, center = Offset(x(reading.takenAt), y(it)))
            }
        }
    }
}

/**
 * Everything that isn't a temperature, recorded through one dialog.
 *
 * A weight is typed in whatever unit the household reads in and converted to kilograms on the way
 * in — the same bargain the baseline temperature makes, and for the same reason: somebody who
 * weighs themselves in pounds does not know the number in kilograms, and asking them to convert it
 * is asking them to get it wrong.
 *
 * Every number is checked as it is typed, against the bounds its kind of measurement carries (see
 * `logic/Vitals`). This is the only place these four are ever entered, so it is the only place that
 * can catch "920" typed for an oxygen saturation — after here the number is a fact about somebody's
 * body, charted, read back to a doctor and handed to Advisor.
 *
 * And it asks **when**, like every other record dialog in the app. It is the one that didn't, and
 * the omission was not only a missing convenience: the repository files a reading against the
 * illness that was open *at the instant it was taken*, so a weight typed up on Sunday for Friday
 * used to land in the wrong story — or in none.
 */
@Composable
private fun LogOtherReadingDialog(
    weightUnit: WeightUnit,
    onDismiss: () -> Unit,
    editing: Reading? = null,
    onConfirm: (ReadingType, Double, Double?, String?, Long) -> Unit
) {
    var type by remember { mutableStateOf(editing?.type ?: ReadingType.HEART_RATE) }
    var primary by remember {
        mutableStateOf(
            when {
                editing == null -> ""
                editing.type == ReadingType.WEIGHT -> Weight.formatBare(editing.value, weightUnit)
                else -> trimAmount(editing.value)
            }
        )
    }
    var secondary by remember { mutableStateOf(editing?.secondaryValue?.let { trimAmount(it) }.orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var at by remember { mutableLongStateOf(editing?.takenAt ?: System.currentTimeMillis()) }

    val options = ReadingType.entries.filter { it != ReadingType.TEMPERATURE }
    val isWeight = type == ReadingType.WEIGHT
    val needsSecond = type == ReadingType.BLOOD_PRESSURE

    // Always the canonical unit by the time it leaves here, and always a believable number: a weight
    // converts from the household's unit on the way through, everything else is bounded where it is.
    val value = if (isWeight) Weight.parseToKilograms(primary, weightUnit) else type.range.parse(primary)
    val diastolic = type.secondaryRange?.parse(secondary)

    // A weight's own parser does the converting, but the complaint is the same one its range carries.
    val primaryComplaint = type.range.complaint.takeIf { primary.isNotBlank() && value == null }
    val secondaryComplaint = type.secondaryRange
        ?.takeIf { secondary.isNotBlank() && diastolic == null }
        ?.complaint

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Measurement" else "Correct this reading") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The kind is fixed on a correction. A heart rate that should have been a weight is
                // not a typo in this row, it is a different row — delete it and record the weight.
                if (editing == null) ChoiceRow(options, type, { type = it }, { it.label })
                SuiteNumberField(
                    label = when {
                        needsSecond -> "Systolic (mmHg)"
                        isWeight -> "${type.label} (${weightUnit.symbol})"
                        else -> "${type.label} (${type.unit})"
                    },
                    value = primary,
                    onValueChange = { primary = it },
                    modifier = Modifier.fillMaxWidth(),
                    decimals = true,
                    supporting = primaryComplaint,
                    isError = primaryComplaint != null
                )
                if (needsSecond) {
                    SuiteNumberField(
                        label = "Diastolic (mmHg)",
                        value = secondary,
                        onValueChange = { secondary = it },
                        modifier = Modifier.fillMaxWidth(),
                        decimals = true,
                        supporting = secondaryComplaint,
                        isError = secondaryComplaint != null
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                SuiteWhenField(
                    value = at,
                    onValueChange = { at = it },
                    label = "Taken",
                    check = { HealthWhen.check(it) }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null && (!needsSecond || diastolic != null),
                onClick = { value?.let { onConfirm(type, it, diastolic, note.ifBlank { null }, at) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
