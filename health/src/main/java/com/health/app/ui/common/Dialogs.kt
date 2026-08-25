package com.health.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.app.data.model.CareKind
import com.health.app.data.model.Medication
import com.health.app.logic.Fever
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.logic.Temperature

/**
 * The four things Health is asked to record in a hurry: a temperature, a dose, a symptom, and what
 * you did about it. Each is one dialog, reachable in one tap from the Today screen, because a form
 * that takes three taps to reach is a record that doesn't get made.
 */

/**
 * Log a temperature. The reading is checked as it's typed by [Temperature.parseToCelsius], and the
 * verdict from [Fever.assess] is shown *before* you save — so an armpit 37.7 announces itself as a
 * fever at the moment that matters, rather than after it has been filed as unremarkable.
 */
@Composable
fun LogTemperatureDialog(
    unit: TempUnit,
    ageMonths: Int?,
    onDismiss: () -> Unit,
    onConfirm: (celsius: Double, site: TempSite, note: String?) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(TempSite.ORAL) }
    var note by remember { mutableStateOf("") }

    val celsius = Temperature.parseToCelsius(text, unit)
    val assessment = celsius?.let { Fever.assess(it, site, ageMonths) }
    val invalid = text.isNotBlank() && celsius == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temperature") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DecimalField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Reading (${unit.symbol})",
                    isError = invalid,
                    supportingText = if (invalid) "That isn't a body temperature — check the number." else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Taken", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = TempSite.entries,
                    selected = site,
                    onSelect = { site = it },
                    label = { it.label }
                )
                assessment?.let { verdict ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(verdict.band.label, style = MaterialTheme.typography.titleSmall)
                            CareBadge(verdict.careLevel)
                        }
                        verdict.reasons.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                DisclaimerText()
            }
        },
        confirmButton = {
            TextButton(
                enabled = celsius != null,
                onClick = { celsius?.let { onConfirm(it, site, note.ifBlank { null }) } }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Log a dose. When the medicine is one of this person's own, its default dose is filled in and only
 * needs confirming; anything given ad hoc can still be typed by name, because a dose that was
 * actually given belongs in the record whether or not it was set up first.
 */
@Composable
fun LogDoseDialog(
    medications: List<Medication>,
    preselected: Medication? = null,
    onDismiss: () -> Unit,
    onConfirm: (medication: Medication?, name: String, amount: Double, unit: String, note: String?) -> Unit
) {
    var selected by remember { mutableStateOf(preselected ?: medications.firstOrNull()) }
    var freeName by remember { mutableStateOf("") }
    var amountText by remember {
        mutableStateOf(selected?.doseAmount?.let { trimAmount(it) } ?: "")
    }
    var unitText by remember { mutableStateOf(selected?.doseUnit ?: "") }
    var note by remember { mutableStateOf("") }

    val amount = amountText.replace(',', '.').toDoubleOrNull()
    val name = selected?.name ?: freeName.trim()
    val canSave = name.isNotBlank() && (amount != null || selected?.doseAmount == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dose given") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (medications.isNotEmpty()) {
                    Text("Medicine", style = MaterialTheme.typography.labelMedium)
                    ChoiceRow(
                        options = medications + listOf<Medication?>(null),
                        selected = selected,
                        onSelect = { medication ->
                            selected = medication
                            amountText = medication?.doseAmount?.let { trimAmount(it) } ?: ""
                            unitText = medication?.doseUnit ?: ""
                        },
                        label = { it?.name ?: "Something else" }
                    )
                }
                if (selected == null) {
                    OutlinedTextField(
                        value = freeName,
                        onValueChange = { freeName = it },
                        label = { Text("Medicine") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = "Amount",
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unitText,
                        onValueChange = { unitText = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
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
                enabled = canSave,
                onClick = {
                    onConfirm(selected, name, amount ?: 0.0, unitText.trim(), note.ifBlank { null })
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Record a symptom and how bad it is. Severity is a number so it can be charted later. */
@Composable
fun AddSymptomDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, severity: Int, note: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var severity by remember { mutableIntStateOf(3) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Symptom") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Severity", style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = (1..5).toList(),
                    selected = severity,
                    onSelect = { severity = it },
                    label = { severityLabel(it) }
                )
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
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), severity, note.ifBlank { null }) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Record what was actually done — fluids, rest, a call to the doctor and what they said. */
@Composable
fun CareNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (kind: CareKind, text: String) -> Unit
) {
    var kind by remember { mutableStateOf(CareKind.NOTE) }
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Care note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceRow(
                    options = CareKind.entries,
                    selected = kind,
                    onSelect = { kind = it },
                    label = { it.label }
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What happened?") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(kind, text.trim()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun severityLabel(severity: Int): String = when (severity) {
    1 -> "1 · barely"
    2 -> "2 · mild"
    3 -> "3 · notable"
    4 -> "4 · bad"
    else -> "5 · severe"
}

/** 5.0 reads as "5"; 2.5 stays "2.5". Dose amounts look wrong with a trailing zero. */
fun trimAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
