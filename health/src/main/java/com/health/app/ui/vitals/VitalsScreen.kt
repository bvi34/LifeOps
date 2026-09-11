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

    val snackbar = remember { SnackbarHostState() }
    UndoHost(vm.undoOffers, snackbar, vm::undo)

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    val temperatures = readings.filter { it.type == ReadingType.TEMPERATURE }

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
                    SectionCard(title = "Temperature") {
                        if (temperatures.size < 2) {
                            Text(
                                "Two readings draw a line. Right now there " +
                                    if (temperatures.size == 1) "is one." else "are none.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            TemperatureChart(
                                readings = temperatures.sortedBy { it.takenAt },
                                unit = unit,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                            val span = temperatures.maxOf { it.takenAt } - temperatures.minOf { it.takenAt }
                            Text(
                                "${temperatures.size} readings over " +
                                    "${DoseSchedule.formatDuration(span)} · " +
                                    "the dashed line is ${Temperature.format(Fever.FEVER_C, unit)}, " +
                                    "where a fever starts.",
                                style = MaterialTheme.typography.bodySmall
                            )
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
                        ReadingRow(reading, unit, weightUnit, onDelete = { vm.delete(reading) })
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
    onDelete: () -> Unit
) {
    val value = when (reading.type) {
        ReadingType.TEMPERATURE -> Temperature.format(reading.value, unit)
        ReadingType.BLOOD_PRESSURE ->
            "${trimAmount(reading.value)}/${reading.secondaryValue?.let { trimAmount(it) } ?: "?"} mmHg"
        ReadingType.WEIGHT -> Weight.format(reading.value, weightUnit)
        else -> "${trimAmount(reading.value)} ${reading.type.unit}"
    }
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
        onDelete = onDelete
    )
}

/**
 * The temperature curve. Deliberately plain: a line, a dot per reading, and one dashed rule at the
 * fever threshold — because the only question this chart is asked is "is it above the line, and is
 * it going up or down". Points are plotted against real time, not reading number, so a gap in the
 * night looks like a gap.
 */
@Composable
private fun TemperatureChart(readings: List<Reading>, unit: TempUnit, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val feverColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    val values = readings.map { it.value }
    // Always include the fever line in the visible range, so "below the line" is visibly below it.
    val minValue = minOf(values.min(), Fever.FEVER_C) - 0.4
    val maxValue = maxOf(values.max(), Fever.FEVER_C) + 0.4
    val firstAt = readings.first().takenAt
    val span = (readings.last().takenAt - firstAt).coerceAtLeast(1L)

    Canvas(modifier = modifier) {
        fun x(at: Long) = ((at - firstAt).toFloat() / span.toFloat()) * size.width
        fun y(value: Double) =
            size.height - (((value - minValue) / (maxValue - minValue)).toFloat() * size.height)

        val feverY = y(Fever.FEVER_C)
        drawLine(
            color = feverColor,
            start = Offset(0f, feverY),
            end = Offset(size.width, feverY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f
        )

        val path = Path().apply {
            readings.forEachIndexed { index, reading ->
                val point = Offset(x(reading.takenAt), y(reading.value))
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        drawPath(path, color = lineColor, style = Stroke(width = 4f))

        readings.forEach { reading ->
            val above = reading.value + (reading.site ?: TempSite.ORAL).toOralOffsetC >= Fever.FEVER_C
            drawCircle(
                color = if (above) feverColor else lineColor,
                radius = 6f,
                center = Offset(x(reading.takenAt), y(reading.value))
            )
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatStamp(readings.first().takenAt), style = MaterialTheme.typography.labelSmall)
        Text(
            "${Temperature.format(values.min(), unit)} – ${Temperature.format(values.max(), unit)}",
            style = MaterialTheme.typography.labelSmall
        )
        Text(formatStamp(readings.last().takenAt), style = MaterialTheme.typography.labelSmall)
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
    onConfirm: (ReadingType, Double, Double?, String?, Long) -> Unit
) {
    var type by remember { mutableStateOf(ReadingType.HEART_RATE) }
    var primary by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var at by remember { mutableLongStateOf(System.currentTimeMillis()) }

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
        title = { Text("Measurement") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ChoiceRow(options, type, { type = it }, { it.label })
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
