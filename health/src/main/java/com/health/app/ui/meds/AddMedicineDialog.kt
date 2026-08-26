package com.health.app.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.CabinetItem
import com.health.app.logic.DrugMonograph
import com.health.app.logic.OpenFdaParser
import com.health.app.logic.ReminderMode
import com.health.app.ui.common.AllergyWarningBanner
import com.health.app.ui.common.ChoiceRow
import com.health.app.ui.common.DecimalField
import com.health.app.ui.common.DisclaimerText

/**
 * Adding a medicine, in one dialog with three optional halves.
 *
 * The old form was "copy the label by hand". This keeps that — every field is still typed, and every
 * limit is still optional — and puts a lookup in front of it, because the *identity* of a medicine
 * is the part a phone can fetch and a person shouldn't have to spell. Search "childrens tylenol",
 * pick the product, and the name, ingredients, strength and form arrive filled in, along with the
 * label's own text to read on the next screen.
 *
 * What the lookup never fills in is the **dose**. Not the amount, not the spacing, not the daily
 * maximum. Those come off the box in front of you, for the person in front of you, and a label's
 * dosing text covers several ages and several products at once — picking a line out of it
 * automatically is precisely the guess this app must not make. The label's own directions are shown
 * beside the fields as a reference, and the fields stay empty until somebody types in them.
 *
 * The two halves — the bottle and the person — are independent. Stock the cupboard without deciding
 * whose it is; or add a medicine for somebody without counting the stock. Ticking both, which is
 * what actually happens when a bottle comes home from the shop, does both in one gesture.
 */
@Composable
fun AddMedicineDialog(
    vm: MedsViewModel,
    personName: String?,
    cabinet: List<CabinetItem>,
    onDismiss: () -> Unit
) {
    val search by vm.search.collectAsStateWithLifecycle()
    val picked = search.picked

    var toCabinet by remember { mutableStateOf(true) }
    var toPerson by remember { mutableStateOf(personName != null) }

    // Product identity — prefilled by a lookup, editable either way.
    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }

    // The bottle.
    var quantity by remember { mutableStateOf("") }
    var quantityUnit by remember { mutableStateOf("mL") }
    var expiry by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var lowStock by remember { mutableStateOf("") }

    // The person's dose rules.
    var doseAmount by remember { mutableStateOf("") }
    var doseUnit by remember { mutableStateOf("mL") }
    var interval by remember { mutableStateOf("") }
    var maxDoses by remember { mutableStateOf("") }
    var maxAmount by remember { mutableStateOf("") }
    var reminderMode by remember { mutableStateOf(ReminderMode.OFF) }
    var reminderTimes by remember { mutableStateOf("08:00,20:00") }

    // Adopt a picked product's identity, without clobbering anything already typed over it.
    LaunchedEffect(picked?.rxcui) {
        picked?.let { monograph ->
            name = monograph.displayName
            strength = monograph.availableStrengths.firstOrNull().orEmpty()
            form = monograph.doseForm.orEmpty()
        }
    }

    // Check what the household has recorded against whatever the form currently names. Keyed on the
    // name *and* the picked concept: picking a product is what upgrades the check from matching a
    // name to matching the label's own ingredient list.
    val allergyWarnings by vm.allergyWarnings.collectAsStateWithLifecycle()
    LaunchedEffect(name, picked?.rxcui, toPerson) {
        if (toPerson) vm.checkAllergies(name, picked?.rxcui) else vm.clearAllergyWarnings()
    }
    DisposableEffect(Unit) { onDispose { vm.clearAllergyWarnings() } }

    fun decimal(text: String) = text.replace(',', '.').toDoubleOrNull()

    val canSave = name.isNotBlank() && (toCabinet || toPerson)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a medicine") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (picked == null) {
                    DrugSearchSection(
                        state = search,
                        onQueryChange = vm::searchDrugs,
                        onPick = { vm.pickCandidate(it) }
                    )
                } else {
                    PickedProductCard(monograph = picked, onClear = vm::clearPicked)
                    // Households buy the same four things repeatedly, and a second row for a bottle
                    // already on the shelf splits its stock and its expiry date in two. Said rather
                    // than prevented: a genuine second bottle is a perfectly ordinary thing to own.
                    cabinet.firstOrNull { it.rxcui != null && it.rxcui == picked.rxcui }?.let { existing ->
                        Text(
                            "The cabinet already has ${existing.displayName}" +
                                (existing.location?.let { " in the $it" } ?: "") +
                                ". Restock that one instead if this is the same pack.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                AllergyWarningBanner(allergyWarnings, personName)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = strength,
                        onValueChange = { strength = it },
                        label = { Text("Strength") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = form,
                        onValueChange = { form = it },
                        label = { Text("Form") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                LabelledSwitch(
                    checked = toCabinet,
                    onCheckedChange = { toCabinet = it },
                    title = "Put it in the cabinet",
                    subtitle = "Track how much is left and when it expires."
                )
                if (toCabinet) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField(quantity, { quantity = it }, "Amount in the pack", Modifier.weight(1f))
                        OutlinedTextField(
                            value = quantityUnit,
                            onValueChange = { quantityUnit = it },
                            label = { Text("Unit") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = expiry,
                        onValueChange = { expiry = it },
                        label = { Text("Expires (YYYY-MM or YYYY-MM-DD)") },
                        singleLine = true,
                        supportingText = { Text("Most boxes print a month — that's enough.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Where it lives (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DecimalField(
                        value = lowStock,
                        onValueChange = { lowStock = it },
                        label = "Tell me it's low at (optional)",
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = "Left blank, Health says so when there isn't enough for a dose."
                    )
                }

                if (personName != null) {
                    HorizontalDivider()
                    LabelledSwitch(
                        checked = toPerson,
                        onCheckedChange = { toPerson = it },
                        title = "Add to $personName's medicines",
                        subtitle = "Their dose, their spacing, their daily limit."
                    )
                }
                if (toPerson && personName != null) {
                    picked?.dosageText?.let { directions ->
                        LabelDirectionsCard(directions)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField(doseAmount, { doseAmount = it }, "Usual dose", Modifier.weight(1f))
                        OutlinedTextField(
                            value = doseUnit,
                            onValueChange = { doseUnit = it },
                            label = { Text("Unit") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    DecimalField(
                        value = interval,
                        onValueChange = { interval = it },
                        label = "Minimum hours between doses",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField(maxDoses, { maxDoses = it }, "Max doses / 24h", Modifier.weight(1f))
                        DecimalField(maxAmount, { maxAmount = it }, "Max amount / 24h", Modifier.weight(1f))
                    }
                    Text(
                        "Leave a limit blank and Health won't enforce it — it tracks what the label " +
                            "says, not what it guesses.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("Remind me", style = MaterialTheme.typography.labelMedium)
                    ChoiceRow(
                        options = ReminderMode.entries,
                        selected = reminderMode,
                        onSelect = { reminderMode = it },
                        label = { it.label }
                    )
                    if (reminderMode == ReminderMode.FIXED_TIMES) {
                        OutlinedTextField(
                            value = reminderTimes,
                            onValueChange = { reminderTimes = it },
                            label = { Text("Times, 24-hour, comma separated") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                DisclaimerText()
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val draft = if (toPerson && personName != null) {
                        MedicationDraft(
                            name = name.trim(),
                            strength = strength.ifBlank { null },
                            form = form.ifBlank { null },
                            doseAmount = decimal(doseAmount),
                            doseUnit = doseUnit.trim(),
                            minIntervalHours = decimal(interval),
                            maxDosesPer24h = decimal(maxDoses)?.toInt(),
                            maxAmountPer24h = decimal(maxAmount),
                            reminderMode = reminderMode,
                            reminderTimes = com.health.app.logic.DoseReminder.parseTimes(reminderTimes)
                        )
                    } else {
                        null
                    }

                    if (toCabinet) {
                        vm.addToCabinet(
                            name = name.trim(),
                            rxcui = picked?.rxcui,
                            brandName = picked?.brandName,
                            strength = strength.ifBlank { null },
                            form = form.ifBlank { null },
                            quantity = decimal(quantity),
                            quantityUnit = quantityUnit.trim(),
                            expiryDate = expiry.ifBlank { null },
                            location = location.ifBlank { null },
                            lowStockThreshold = decimal(lowStock),
                            alsoForProfile = draft
                        )
                    } else if (draft != null) {
                        vm.addMedication(draft, rxcui = picked?.rxcui)
                    }
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The search box and its results.
 *
 * The empty state says what the lookup is and what it sends, in the dialog rather than buried in a
 * settings screen. An app that has never touched the network suddenly asking about a drug name
 * deserves to explain itself at the moment it does it.
 */
@Composable
private fun DrugSearchSection(
    state: DrugSearchState,
    onQueryChange: (String) -> Unit,
    onPick: (com.health.app.logic.DrugCandidate) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search for a medicine") },
            placeholder = { Text("Children's Tylenol, ibuprofen…") },
            singleLine = true,
            trailingIcon = {
                if (state.searching) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Looks the medicine up in RxNorm and openFDA — two public U.S. government drug " +
                "references. Only what you type here leaves the device; nobody's records do. " +
                "Skip it and type the medicine in yourself if you'd rather.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        state.results.take(MAX_RESULTS).forEach { candidate ->
            ListItem(
                headlineContent = { Text(candidate.name) },
                supportingContent = {
                    Text(candidate.subtitle, style = MaterialTheme.typography.bodySmall)
                },
                trailingContent = {
                    if (state.fetchingRxcui == candidate.rxcui) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.clickable { onPick(candidate) }
            )
        }
    }
}

/** What the lookup found, with the way back out of it. */
@Composable
private fun PickedProductCard(monograph: DrugMonograph, onClear: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monograph.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) { Text("Change") }
            }
            monograph.descriptor?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            monograph.ingredients.takeIf { it.isNotEmpty() }?.let {
                Text("Contains ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
            monograph.schedule?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (monograph.hasLabel) {
                Text(
                    "Label found — readable from the cabinet once this is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The label's own directions, shown next to the dose fields and *not* into them.
 *
 * This is the most important restraint in the feature. The text below covers several ages, several
 * weights and often several products; choosing which line applies to the person in front of you is
 * a judgement, and Health does not make judgements it wasn't given. So it reads the box out and
 * leaves the typing to a human.
 */
@Composable
private fun LabelDirectionsCard(directions: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                OpenFdaParser.SECTION_ORDER.first { it.first == OpenFdaParser.DOSAGE }.second,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(directions.take(MAX_DIRECTIONS_CHARS), style = MaterialTheme.typography.bodySmall)
            Text(
                "Read from the label. Health won't fill the dose in for you — that depends on who " +
                    "it's for, and it's yours to decide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LabelledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private const val MAX_RESULTS = 8

/** A dialog is not a document reader; the full text is on the label sheet. */
private const val MAX_DIRECTIONS_CHARS = 600
