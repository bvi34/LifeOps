package com.health.app.ui.vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature
import com.health.app.ui.common.*

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

    var showTemp by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(false) }

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    val temperatures = readings.filter { it.type == ReadingType.TEMPERATURE }

    Scaffold(
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
                        ReadingRow(reading, unit, onDelete = { vm.delete(reading) })
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
            onDismiss = { showOther = false },
            onConfirm = { type, value, secondary, note ->
                vm.logOther(type, value, secondary, note)
                showOther = false
            }
        )
    }
}

@Composable
private fun ReadingRow(reading: Reading, unit: TempUnit, onDelete: () -> Unit) {
    val value = when (reading.type) {
        ReadingType.TEMPERATURE -> Temperature.format(reading.value, unit)
        ReadingType.BLOOD_PRESSURE ->
            "${trimAmount(reading.value)}/${reading.secondaryValue?.let { trimAmount(it) } ?: "?"} mmHg"
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

@Composable
private fun LogOtherReadingDialog(
    onDismiss: () -> Unit,
    onConfirm: (ReadingType, Double, Double?, String?) -> Unit
) {
    var type by remember { mutableStateOf(ReadingType.HEART_RATE) }
    var primary by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val options = ReadingType.entries.filter { it != ReadingType.TEMPERATURE }
    val value = primary.replace(',', '.').toDoubleOrNull()
    val diastolic = secondary.replace(',', '.').toDoubleOrNull()
    val needsSecond = type == ReadingType.BLOOD_PRESSURE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Measurement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceRow(options, type, { type = it }, { it.label })
                DecimalField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = if (needsSecond) "Systolic (mmHg)" else "${type.label} (${type.unit})",
                    modifier = Modifier.fillMaxWidth()
                )
                if (needsSecond) {
                    DecimalField(
                        value = secondary,
                        onValueChange = { secondary = it },
                        label = "Diastolic (mmHg)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null && (!needsSecond || diastolic != null),
                onClick = { value?.let { onConfirm(type, it, diastolic, note.ifBlank { null }) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
