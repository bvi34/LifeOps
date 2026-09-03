@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maintenance.app.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.CoverageView
import com.maintenance.app.data.model.LoanView
import com.maintenance.app.logic.AssetAttributes
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.AttributeCheck
import com.maintenance.app.logic.AssetAttributeSpec
import com.maintenance.app.logic.AttributeInput
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.MeterUnit
import com.maintenance.app.logic.Money
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.UpkeepPlan
import com.maintenance.app.ui.common.toEpochMillis
import com.maintenance.app.ui.common.toLocalDate
import com.maintenance.app.ui.common.todayMillis
import com.operations.suite.ui.pickers.SuiteDateField
import com.operations.suite.ui.fields.SuiteMoneyField
import com.operations.suite.ui.fields.SuiteNumberField
import com.operations.suite.ui.fields.SuiteTextField

/**
 * The dialogs the asset page edits through.
 *
 * They share three habits. Each one **opens on what is already there**, so editing is never
 * retyping. Each one **holds text, not parsed values**, and converts on the way out — a half-typed
 * "12." should not vanish under the cursor. And each **confirm button is enabled on the one field
 * that makes the row meaningful** (a title, a provider, a principal) rather than on every field
 * being filled: a policy whose number you have not found yet is still worth having on the docket.
 */

/** Yes/no, with the consequence spelled out. Used for every delete in the app. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The fields this *kind* asks for, drawn from `logic/AssetKind` rather than written out.
 *
 * Shared by the add and the edit dialogs so the two never drift: whatever a vehicle is asked for
 * when it is typed in is exactly what it is asked for afterwards, already validated, already
 * hinted. Nothing here is required — every one of them is blank on the day you add the car, and a
 * form that refused the row until you went and read the door jamb is a form that never gets the car
 * into the app at all.
 */
@Composable
fun KindAttributeFields(
    kind: AssetKind,
    values: Map<String, String>,
    onChange: (key: String, value: String) -> Unit
) {
    kind.attributes.forEach { spec ->
        val value = values[spec.key].orEmpty()
        val problem = AssetAttributes.problem(spec, value)
        when (spec.input) {
            AttributeInput.NUMBER -> SuiteNumberField(
                label = spec.label,
                value = value,
                onValueChange = { onChange(spec.key, it) },
                supporting = problem ?: spec.hint,
                isError = problem != null,
                decimals = spec.check == AttributeCheck.DECIMAL
            )
            AttributeInput.CHOICE, AttributeInput.CHOICES -> OptionField(
                spec = spec,
                value = value,
                onToggle = { key -> onChange(spec.key, AssetAttributes.toggle(spec, value, key)) }
            )
            else -> SuiteTextField(
                label = spec.label,
                value = value,
                onValueChange = { onChange(spec.key, it) },
                singleLine = spec.input != AttributeInput.MULTILINE,
                supporting = problem ?: spec.hint,
                isError = problem != null,
                capitalise = KeyboardCapitalization.Words
            )
        }
    }
}

/**
 * A field that is picked rather than typed.
 *
 * One composable for both kinds of picker, because from here they differ only in what a tap does —
 * which is [AssetAttributes.toggle]'s business, not this file's. The chips wrap rather than scroll:
 * a row you have to swipe hides options, and hidden options on a field whose whole purpose is
 * *announcing what you have* is the failure mode worth avoiding.
 *
 * The detail under a chip is shown for the selected ones only. Twelve two-line chips is a wall; the
 * one you have just chosen explaining itself is a confirmation.
 */
@Composable
private fun OptionField(
    spec: AssetAttributeSpec,
    value: String,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(spec.label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spec.options.forEach { option ->
                FilterChip(
                    selected = AssetAttributes.isChosen(spec, value, option.key),
                    onClick = { onToggle(option.key) },
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        val chosen = AssetAttributes.chosen(spec, value).mapNotNull { spec.option(it)?.detail }
        val supporting = chosen.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: spec.hint
        supporting?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Everything about the asset itself, including the fields its *kind* asks for.
 *
 * The kind-specific half is generated from `logic/AssetKind` rather than written out here, which is
 * what makes adding a kind an authoring job: a new entry there grows its own fields in this dialog,
 * already validated, already stored.
 */
@Composable
fun EditAssetDialog(
    asset: Asset,
    onDismiss: () -> Unit,
    onSave: (Asset, Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf(asset.name) }
    var make by remember { mutableStateOf(asset.make.orEmpty()) }
    var model by remember { mutableStateOf(asset.model.orEmpty()) }
    var year by remember { mutableStateOf(asset.year?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(asset.notes.orEmpty()) }
    var purchasedAt by remember { mutableStateOf(asset.purchasedAt) }
    var paidText by remember { mutableStateOf(asset.purchasePriceCents?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var paidCents by remember { mutableStateOf(asset.purchasePriceCents) }
    var worthText by remember { mutableStateOf(asset.currentValueCents?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var worthCents by remember { mutableStateOf(asset.currentValueCents) }
    val attributes = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            asset.kind.attributes.forEach { put(it.key, asset.attribute(it.key).orEmpty()) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${asset.name}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SuiteTextField(label = "Name", value = name, onValueChange = { name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteTextField(label = "Make", value = make, onValueChange = { make = it }, modifier = Modifier.weight(1f))
                    SuiteTextField(label = "Model", value = model, onValueChange = { model = it }, modifier = Modifier.weight(1f))
                }
                SuiteNumberField(label = "Year", value = year, onValueChange = { year = it.take(4) })

                KindAttributeFields(
                    kind = asset.kind,
                    values = attributes,
                    onChange = { key, value -> attributes[key] = value }
                )

                SuiteDateField(label = "Bought", millis = purchasedAt, onMillisChange = { purchasedAt = it })
                SuiteMoneyField(
                    label = "Paid",
                    text = paidText,
                    onChange = { text, cents -> paidText = text; paidCents = cents }
                )
                SuiteMoneyField(
                    label = "Worth now",
                    text = worthText,
                    onChange = { text, cents -> worthText = text; worthCents = cents },
                    supporting = "Whatever you last looked up. Nothing here updates it for you."
                )
                SuiteTextField(label = "Notes", value = notes, onValueChange = { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        asset.copy(
                            name = name,
                            make = make,
                            model = model,
                            year = year.toIntOrNull(),
                            notes = notes,
                            purchasedAt = purchasedAt,
                            purchasePriceCents = if (paidText.isBlank()) null else paidCents,
                            currentValueCents = if (worthText.isBlank()) null else worthCents
                        ),
                        // Normalised on the way out — a VIN typed in lower case is stored the way it
                        // is written on the title, and a blank clears the field rather than storing "".
                        asset.kind.attributes.associate { spec ->
                            spec.key to AssetAttributes.normalise(spec, attributes[spec.key].orEmpty())
                        }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A standing job and how often it comes round.
 *
 * Both intervals are offered on every asset that has a meter, and the dialog says out loud what
 * happens when both are set — because "whichever comes first" is the rule everybody's owner's
 * manual uses and nobody's app explains.
 */
@Composable
fun PlanDialog(
    plan: UpkeepPlan?,
    meterUnit: MeterUnit?,
    onDismiss: () -> Unit,
    onSave: (title: String, everyDays: Int?, everyMeter: Long?, notes: String?, publish: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(plan?.title.orEmpty()) }
    var days by remember { mutableStateOf(plan?.everyDays?.toString().orEmpty()) }
    var meter by remember { mutableStateOf(plan?.everyMeter?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(plan?.notes.orEmpty()) }
    var publish by remember { mutableStateOf(plan?.publishToLifeOps ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (plan == null) "Something that comes round" else "Edit ${plan.title}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SuiteTextField(label = "What", value = title, onValueChange = { title = it })

                // Milestones come from a schedule pack and are shown rather than edited: they are a
                // list, not a number, and a text box that turned "60,000, 120,000" into one wrong
                // figure would be worse than not offering it. Everything else about the plan is
                // editable, and the intervals below still apply alongside them.
                val milestones = plan?.atMeter.orEmpty()
                if (milestones.isNotEmpty()) {
                    Text(
                        "Due at " + milestones.joinToString(", ") { meterUnit?.format(it) ?: MeterUnit.group(it) } +
                            " — from the schedule, and not editable here yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SuiteNumberField(label = "Every … days", value = days, onValueChange = { days = it })
                if (meterUnit != null) {
                    SuiteNumberField(
                        label = "Every … ${meterUnit.noun}",
                        value = meter,
                        onValueChange = { meter = it }
                    )
                    Text(
                        "Set both and whichever comes first wins — the way a service schedule is actually written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SuiteTextField(label = "Notes", value = notes, onValueChange = { notes = it }, singleLine = false)

                // The seam, said plainly and switchable per plan. "Change the furnace filter"
                // belongs on a week; "check the roof after a storm" does not.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Put it on the LifeOps week", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A task dated the day it's due — it waits in LifeOps' future queue until " +
                                "that week opens. Tick it there and it's logged here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = publish, onCheckedChange = { publish = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        title.trim(),
                        days.toIntOrNull(),
                        meter.toLongOrNull(),
                        notes.takeIf { it.isNotBlank() },
                        publish
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Log work that was done — the only thing in the app that moves a schedule's clock.
 *
 * The meter box is pre-filled with the last known reading rather than left empty: the mileage at a
 * service is almost always "about what it is now", and a pre-filled number gets corrected while an
 * empty one gets skipped — and a skipped one is what leaves a mileage interval undatable.
 *
 * [suggestVendors] offers back names already used **on any asset**, because the garage that did the
 * truck is the one you would ring about the mower. They are chips rather than a picker: a name still
 * gets typed, and the suggestion only saves you from spelling it a third way — which is what makes
 * "who did the brakes last time" answerable later.
 */
@Composable
fun LogServiceDialog(
    title: String,
    planId: String?,
    meterUnit: MeterUnit?,
    suggestedMeter: Long?,
    suggestVendors: (String) -> List<String> = { emptyList() },
    onDismiss: () -> Unit,
    onSave: (planId: String?, title: String, vendor: String?, at: Long, costCents: Long, meter: Long?, notes: String?) -> Unit
) {
    var what by remember { mutableStateOf(title) }
    var vendor by remember { mutableStateOf("") }
    var at by remember { mutableStateOf(todayMillis()) }
    var costText by remember { mutableStateOf("") }
    var costCents by remember { mutableStateOf<Long?>(null) }
    var meter by remember { mutableStateOf(suggestedMeter?.toString().orEmpty()) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a service") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SuiteTextField(label = "What was done", value = what, onValueChange = { what = it })
                SuiteTextField(label = "Who did it", value = vendor, onValueChange = { vendor = it }, capitalise = KeyboardCapitalization.Words)
                val known = suggestVendors(vendor)
                if (known.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        known.forEach { name ->
                            AssistChip(
                                onClick = { vendor = name },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                SuiteDateField(label = "When", millis = at, onMillisChange = { at = it ?: todayMillis() }, clearable = false)
                SuiteMoneyField(
                    label = "Cost",
                    text = costText,
                    onChange = { text, cents -> costText = text; costCents = cents },
                    supporting = "Leave it blank if it was free or you did it yourself."
                )
                if (meterUnit != null) {
                    SuiteNumberField(
                        label = meterUnit.reading,
                        value = meter,
                        onValueChange = { meter = it },
                        supporting = "Filed as a reading too — it's what dates the next one."
                    )
                }
                SuiteTextField(label = "Notes", value = notes, onValueChange = { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = what.isNotBlank(),
                onClick = {
                    onSave(
                        planId,
                        what.trim(),
                        vendor.takeIf { it.isNotBlank() },
                        at,
                        costCents ?: 0L,
                        meter.toLongOrNull(),
                        notes.takeIf { it.isNotBlank() }
                    )
                }
            ) { Text("Log it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A number off a dial, on a day. Two of these are what turn a mileage interval into a date. */
@Composable
fun ReadingDialog(
    unit: MeterUnit,
    latest: Long?,
    onDismiss: () -> Unit,
    onSave: (value: Long, at: Long) -> Unit
) {
    var value by remember { mutableStateOf(latest?.toString().orEmpty()) }
    var at by remember { mutableStateOf(todayMillis()) }
    val parsed = value.toLongOrNull()
    val backwards = parsed != null && latest != null && parsed < latest

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit.reading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuiteNumberField(
                    label = "Reading",
                    value = value,
                    onValueChange = { value = it },
                    supporting = if (backwards) {
                        // Not refused: meters do get replaced, and the rate simply restarts from here.
                        "Lower than the last one — the rate will be measured from here on."
                    } else {
                        latest?.let { "Last was ${unit.format(it)}" }
                    }
                )
                SuiteDateField(label = "Read on", millis = at, onMillisChange = { at = it ?: todayMillis() }, clearable = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed > 0L,
                onClick = { parsed?.let { onSave(it, at) } }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A mortgage or a loan, as the note reads.
 *
 * Only the terms are typed: principal, rate, term, start, and the payment when it differs from what
 * the note works out to. The balance, the interest, the payoff date and the equity are all derived
 * — so there is deliberately no "current balance" box to keep up to date, which is the field that
 * makes every other app's mortgage page wrong within a month.
 */
@Composable
fun LoanDialog(
    loan: LoanView?,
    onDismiss: () -> Unit,
    onSave: (
        id: String?, label: String, lender: String?, accountRef: String?, principalCents: Long,
        annualRateBps: Int, termMonths: Int, paymentCents: Long?, escrowCents: Long,
        startEpochDay: Long?, notes: String?
    ) -> Unit
) {
    var label by remember { mutableStateOf(loan?.label ?: "Mortgage") }
    var lender by remember { mutableStateOf(loan?.lender.orEmpty()) }
    var accountRef by remember { mutableStateOf(loan?.accountRef.orEmpty()) }
    var principalText by remember { mutableStateOf(loan?.terms?.principalCents?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var principalCents by remember { mutableStateOf(loan?.terms?.principalCents) }
    var rate by remember { mutableStateOf(loan?.terms?.annualRateBps?.let { (it / 100.0).toString() }.orEmpty()) }
    var years by remember { mutableStateOf(loan?.terms?.termMonths?.let { (it / 12).toString() }.orEmpty()) }
    var paymentText by remember { mutableStateOf(loan?.terms?.paymentCents?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var paymentCents by remember { mutableStateOf(loan?.terms?.paymentCents) }
    var escrowText by remember { mutableStateOf(loan?.terms?.escrowCents?.takeIf { it > 0L }?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var escrowCents by remember { mutableStateOf(loan?.terms?.escrowCents) }
    var startAt by remember { mutableStateOf(loan?.startEpochDay?.let { toEpochMillis(java.time.LocalDate.ofEpochDay(it)) }) }
    var notes by remember { mutableStateOf(loan?.notes.orEmpty()) }

    val rateBps = Loan.parseRate(rate)
    val termMonths = years.toIntOrNull()?.times(12)
    val scheduled = if (principalCents != null && rateBps != null && termMonths != null) {
        Loan.scheduledPayment(principalCents!!, rateBps, termMonths)
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (loan == null) "Add a loan" else "Edit ${loan.label}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SuiteTextField(label = "What it is", value = label, onValueChange = { label = it })
                SuiteTextField(label = "Lender", value = lender, onValueChange = { lender = it }, capitalise = KeyboardCapitalization.Words)
                SuiteNumberField(
                    label = "Account (last 4)",
                    value = accountRef,
                    onValueChange = { accountRef = it.take(4) },
                    supporting = "Four digits is enough to recognise it. Nothing here needs the whole number."
                )
                SuiteMoneyField(
                    label = "Amount borrowed",
                    text = principalText,
                    onChange = { text, cents -> principalText = text; principalCents = cents }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuiteTextField(
                        label = "Rate %",
                        value = rate,
                        onValueChange = { rate = it },
                        supporting = if (rate.isNotBlank() && rateBps == null) "A percentage, like 6.25" else null,
                        isError = rate.isNotBlank() && rateBps == null,
                        modifier = Modifier.weight(1f)
                    )
                    SuiteNumberField(label = "Years", value = years, onValueChange = { years = it.take(2) }, modifier = Modifier.weight(1f))
                }
                SuiteDateField(label = "First payment", millis = startAt, onMillisChange = { startAt = it })
                SuiteMoneyField(
                    label = "Payment",
                    text = paymentText,
                    onChange = { text, cents -> paymentText = text; paymentCents = cents },
                    supporting = scheduled?.let { "The note works out to ${Money.format(it)} — leave blank unless you pay more." }
                )
                SuiteMoneyField(
                    label = "Escrow",
                    text = escrowText,
                    onChange = { text, cents -> escrowText = text; escrowCents = cents },
                    supporting = "Taxes and insurance collected with the payment. Never treated as debt."
                )
                SuiteTextField(label = "Notes", value = notes, onValueChange = { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = principalCents != null && principalCents!! > 0L && rateBps != null && termMonths != null && termMonths > 0,
                onClick = {
                    onSave(
                        loan?.id,
                        label.trim().ifBlank { "Loan" },
                        lender.takeIf { it.isNotBlank() },
                        accountRef.takeIf { it.isNotBlank() },
                        principalCents ?: 0L,
                        rateBps ?: 0,
                        termMonths ?: 0,
                        if (paymentText.isBlank()) null else paymentCents,
                        escrowCents.takeIf { escrowText.isNotBlank() } ?: 0L,
                        startAt?.let { toLocalDate(it).toEpochDay() },
                        notes.takeIf { it.isNotBlank() }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Insurance, a warranty, a registration — the paperwork with a date on it. */
@Composable
fun CoverageDialog(
    view: CoverageView?,
    onDismiss: () -> Unit,
    onSave: (
        id: String?, kind: CoverageKind, provider: String, policyNumber: String?, premiumCents: Long,
        period: PremiumPeriod, startsAt: Long?, expiresAt: Long?, notes: String?
    ) -> Unit
) {
    val existing = view?.coverage
    var kind by remember { mutableStateOf(existing?.kind ?: CoverageKind.INSURANCE) }
    var provider by remember { mutableStateOf(existing?.provider.orEmpty()) }
    var policy by remember { mutableStateOf(existing?.policyNumber.orEmpty()) }
    var premiumText by remember { mutableStateOf(existing?.premiumCents?.takeIf { it > 0L }?.let { Money.format(it, symbol = "") }.orEmpty()) }
    var premiumCents by remember { mutableStateOf(existing?.premiumCents) }
    var period by remember { mutableStateOf(existing?.period ?: PremiumPeriod.ANNUAL) }
    var startsAt by remember { mutableStateOf(existing?.startsAt) }
    var expiresAt by remember { mutableStateOf(existing?.expiresAt) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add cover" else "Edit ${existing.kind.label}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ChipRow(
                    options = CoverageKind.entries.map { it to it.label },
                    selected = kind,
                    onSelect = { kind = it }
                )
                SuiteTextField(label = "Provider", value = provider, onValueChange = { provider = it }, capitalise = KeyboardCapitalization.Words)
                SuiteTextField(label = "Policy number", value = policy, onValueChange = { policy = it })
                SuiteMoneyField(
                    label = "Premium",
                    text = premiumText,
                    onChange = { text, cents -> premiumText = text; premiumCents = cents }
                )
                ChipRow(
                    options = PremiumPeriod.entries.map { it to it.label },
                    selected = period,
                    onSelect = { period = it }
                )
                SuiteDateField(label = "Starts", millis = startsAt, onMillisChange = { startsAt = it })
                SuiteDateField(
                    label = "Renews or expires",
                    millis = expiresAt,
                    onMillisChange = { expiresAt = it },
                    placeholder = "No end date"
                )
                SuiteTextField(label = "Notes", value = notes, onValueChange = { notes = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = provider.isNotBlank(),
                onClick = {
                    onSave(
                        existing?.id,
                        kind,
                        provider.trim(),
                        policy.takeIf { it.isNotBlank() },
                        premiumCents.takeIf { premiumText.isNotBlank() } ?: 0L,
                        period,
                        startsAt,
                        expiresAt,
                        notes.takeIf { it.isNotBlank() }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A scrolling row of chips over any small set of options. */
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
