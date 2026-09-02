package com.maintenance.app.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.model.CoverageView
import com.maintenance.app.data.model.LoanView
import com.maintenance.app.data.model.PlanView
import com.maintenance.app.data.model.ServiceRecord
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.MeterUnit
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.SchedulePacks
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.UpkeepPlan
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.LabeledValue
import com.maintenance.app.ui.common.SectionCard
import com.maintenance.app.ui.common.StatusPill
import com.maintenance.app.ui.common.formatDate
import com.maintenance.app.ui.common.formatDay
import com.maintenance.app.ui.common.formatMonth
import com.maintenance.app.ui.common.money
import com.maintenance.app.ui.common.statusColor

/**
 * What this asset needs, and the one button that both records it and moves its clock.
 *
 * "Log it" is the only way a schedule advances — there is no reset — so the button that satisfies a
 * plan is the same button that writes what was done, how much it cost and what the odometer read.
 * That is the whole reason a service history in this app has no holes in it.
 */
@Composable
fun UpkeepTab(vm: AssetDetailViewModel, detail: AssetDetail) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UpkeepPlan?>(null) }
    var logging by remember { mutableStateOf<PlanView?>(null) }
    var deleting by remember { mutableStateOf<UpkeepPlan?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "add") {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add something that comes round")
            }
        }

        if (detail.plans.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    headline = "No schedule yet",
                    detail = "Oil every 5,000 miles or 6 months; a filter every 90 days; a gutter clean every autumn. " +
                        "Either interval, or both — whichever comes first wins."
                )
            }
        }

        items(detail.plans, key = { it.plan.id }) { view ->
            PlanCard(
                view = view,
                meterUnit = detail.meter?.unit,
                onLog = { logging = view },
                onEdit = { editing = view.plan },
                onTogglePause = { vm.setPlanActive(view.plan.id, !view.plan.active) },
                onDelete = { deleting = view.plan }
            )
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }

    if (adding) {
        PlanDialog(
            plan = null,
            meterUnit = detail.meter?.unit,
            onDismiss = { adding = false },
            onSave = { title, everyDays, everyMeter, notes, publish ->
                adding = false
                vm.addPlan(title, everyDays, everyMeter, notes, publish)
            }
        )
    }

    editing?.let { plan ->
        PlanDialog(
            plan = plan,
            meterUnit = detail.meter?.unit,
            onDismiss = { editing = null },
            onSave = { title, everyDays, everyMeter, notes, publish ->
                editing = null
                vm.updatePlan(
                    plan.copy(
                        title = title,
                        everyDays = everyDays,
                        everyMeter = everyMeter,
                        notes = notes,
                        publishToLifeOps = publish
                    )
                )
            }
        )
    }

    logging?.let { view ->
        LogServiceDialog(
            title = view.plan.title,
            planId = view.plan.id,
            meterUnit = detail.meter?.unit,
            suggestedMeter = detail.meter?.current,
            onDismiss = { logging = null },
            onSave = { planId, title, vendor, at, cost, meter, notes ->
                logging = null
                vm.logService(planId, title, vendor, at, cost, meter, notes)
            }
        )
    }

    deleting?.let { plan ->
        ConfirmDialog(
            title = "Stop tracking \"${plan.title}\"?",
            body = "The schedule goes; everything you logged against it stays in the history.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; vm.deletePlan(plan.id) }
        )
    }
}

/** One plan: what it is, how often, where it stands, and the four things you can do to it. */
@Composable
private fun PlanCard(
    view: PlanView,
    meterUnit: MeterUnit?,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    onDelete: () -> Unit
) {
    val plan = view.plan
    SectionCard(title = plan.title, trailing = { StatusPill(view.verdict.status) }) {
        Text(
            intervalLine(plan, meterUnit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            view.verdict.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(view.verdict.status)
        )
        weekLine(view)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        // Both prompts say the same thing in their own words: you don't tick this, you do the thing
        // and it ticks itself. See `logic/PlanKind`.
        when (plan.kind) {
            PlanKind.METER_READING ->
                "Type the reading in and this ticks itself off — here and on the week."
            PlanKind.RECALL_CHECK ->
                "Press Check recalls above and this ticks itself off — here and on the week."
            PlanKind.UPKEEP -> null
        }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        plan.sourcePack?.let { packId ->
            SchedulePacks.byId(packId)?.let { pack ->
                Text(
                    "From ${pack.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val lastDone = plan.lastDoneAt?.let { at ->
            val meter = plan.lastDoneMeter?.let { m -> " at ${meterUnit?.format(m) ?: MeterUnit.group(m)}" }.orEmpty()
            "Last done ${formatDay(at)}$meter"
        } ?: "Never logged"
        Text(lastDone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLog) { Text("Log it") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onTogglePause) { Text(if (plan.active) "Pause" else "Resume") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/**
 * Where this plan stands with the LifeOps week.
 *
 * Null when there is nothing to say — a plan that publishes and simply hasn't been put on a week
 * yet says nothing rather than reporting its own plumbing. The two states worth a line are "it is
 * over there, dated" and "you have deliberately kept it out of the week".
 */
@Composable
private fun weekLine(view: PlanView): String? = when {
    !view.plan.publishToLifeOps -> "Kept off the LifeOps week"
    view.link.taskId == null -> null
    view.link.publishedDue != null -> "On the LifeOps week for ${formatDate(view.link.publishedDue)}"
    else -> "On the LifeOps week"
}

/**
 * "Every 5,000 mi or 6 months" — the line under a plan's name.
 *
 * Milestones read as the numbers they are ("at 100,000 mi") rather than as an interval, because
 * that is the difference the whole `atMeter` idea exists to preserve: a cadence is measured from the
 * last time you did it, a milestone is a number on the dial.
 */
private fun intervalLine(plan: UpkeepPlan, meterUnit: MeterUnit?): String {
    fun meter(value: Long) = meterUnit?.format(value) ?: MeterUnit.group(value)

    val parts = buildList {
        if (plan.atMeter.isNotEmpty()) {
            add("at " + plan.atMeter.joinToString(", ") { meter(it) })
        } else {
            plan.everyMeter?.let { add("every ${meter(it)}") }
        }
        plan.everyDays?.let { add("every ${intervalDays(it)}") }
    }
    if (parts.isEmpty()) return "No interval — done when you say so"
    return parts.joinToString(" or ").replaceFirstChar { it.uppercase() } +
        if (parts.size > 1) ", whichever comes first" else ""
}

private fun intervalDays(days: Int): String = when {
    days % 365 == 0 -> "${days / 365} ${if (days == 365) "year" else "years"}"
    days % 30 == 0 -> "${days / 30} months"
    days % 7 == 0 -> "${days / 7} weeks"
    else -> "$days days"
}

/**
 * Everything that has been done to this asset, newest first, and the meter readings alongside it.
 *
 * Readings are shown here rather than on their own screen because they are the same kind of fact —
 * something that was true on a day — and because the most common reason to look at the history is
 * to work out what the mileage was doing at the time.
 */
@Composable
fun HistoryTab(vm: AssetDetailViewModel, detail: AssetDetail) {
    var logging by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServiceRecord?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { logging = true }, modifier = Modifier.weight(1f)) {
                    Text("Log a service")
                }
                if (detail.meter != null) {
                    OutlinedButton(onClick = { reading = true }, modifier = Modifier.weight(1f)) {
                        Text("Add a reading")
                    }
                }
            }
        }

        if (detail.records.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    headline = "Nothing logged yet",
                    detail = "Everything you record here is what makes the costs and the schedules mean anything."
                )
            }
        }

        items(detail.records, key = { it.id }) { record ->
            SectionCard(
                title = record.title,
                trailing = { Text(money(record.costCents), style = MaterialTheme.typography.titleSmall) }
            ) {
                Text(
                    listOfNotNull(
                        formatDay(record.performedAt),
                        record.vendor,
                        record.meterValue?.let { detail.meter?.unit?.format(it) ?: MeterUnit.group(it) }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                record.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { deleting = record }) { Text("Delete") }
            }
        }

        if (detail.readings.size > 1) {
            item(key = "readings") {
                SectionCard(title = detail.meter?.unit?.reading ?: "Readings") {
                    detail.readings.sortedByDescending { it.readAt }.take(8).forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatDay(entry.readAt), style = MaterialTheme.typography.bodySmall)
                            Text(
                                detail.meter?.unit?.format(entry.value) ?: MeterUnit.group(entry.value),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }

    if (logging) {
        LogServiceDialog(
            title = "",
            planId = null,
            meterUnit = detail.meter?.unit,
            suggestedMeter = detail.meter?.current,
            onDismiss = { logging = false },
            onSave = { planId, title, vendor, at, cost, meter, notes ->
                logging = false
                vm.logService(planId, title, vendor, at, cost, meter, notes)
            }
        )
    }

    val meter = detail.meter
    if (reading && meter != null) {
        ReadingDialog(
            unit = meter.unit,
            latest = meter.current,
            onDismiss = { reading = false },
            onSave = { value, at ->
                reading = false
                vm.addReading(value, at)
            }
        )
    }

    deleting?.let { record ->
        ConfirmDialog(
            title = "Delete \"${record.title}\"?",
            body = "It comes out of the history and out of the costs.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; vm.deleteRecord(record.id) }
        )
    }
}

/**
 * What the thing owes and what it costs: the loan, the policies, and the running total.
 *
 * Every figure on a loan card is derived from the note's own terms rather than stored, so nothing
 * here can be a number somebody forgot to update. The costs card refuses to annualise a history
 * shorter than a year, which is why "per year" is sometimes simply absent.
 */
@Composable
fun MoneyTab(vm: AssetDetailViewModel, detail: AssetDetail) {
    var editingLoan by remember { mutableStateOf<LoanView?>(null) }
    var addingLoan by remember { mutableStateOf(false) }
    var deletingLoan by remember { mutableStateOf<LoanView?>(null) }
    var editingCoverage by remember { mutableStateOf<CoverageView?>(null) }
    var addingCoverage by remember { mutableStateOf(false) }
    var deletingCoverage by remember { mutableStateOf<CoverageView?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "costs") { CostsCard(detail) }

        item(key = "loan-add") {
            OutlinedButton(onClick = { addingLoan = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (detail.loans.isEmpty()) "Add a mortgage or loan" else "Add another loan")
            }
        }

        items(detail.loans, key = { it.id }) { loan ->
            LoanCard(loan = loan, onEdit = { editingLoan = loan }, onDelete = { deletingLoan = loan })
        }

        item(key = "coverage-add") {
            OutlinedButton(onClick = { addingCoverage = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add insurance, a warranty or a registration")
            }
        }

        items(detail.coverages, key = { it.coverage.id }) { view ->
            CoverageCard(view = view, onEdit = { editingCoverage = view }, onDelete = { deletingCoverage = view })
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }

    if (addingLoan || editingLoan != null) {
        LoanDialog(
            loan = editingLoan,
            onDismiss = { addingLoan = false; editingLoan = null },
            onSave = { id, label, lender, ref, principal, rateBps, term, payment, escrow, start, notes ->
                addingLoan = false
                editingLoan = null
                vm.saveLoan(id, label, lender, ref, principal, rateBps, term, payment, escrow, start, notes)
            }
        )
    }

    if (addingCoverage || editingCoverage != null) {
        CoverageDialog(
            view = editingCoverage,
            onDismiss = { addingCoverage = false; editingCoverage = null },
            onSave = { id, kind, provider, policy, premium, period, starts, expires, notes ->
                addingCoverage = false
                editingCoverage = null
                vm.saveCoverage(id, kind, provider, policy, premium, period, starts, expires, notes)
            }
        )
    }

    deletingLoan?.let { loan ->
        ConfirmDialog(
            title = "Delete ${loan.label}?",
            body = "The balance, the payoff date and the equity go with it.",
            confirm = "Delete",
            onDismiss = { deletingLoan = null },
            onConfirm = { deletingLoan = null; vm.deleteLoan(loan.id) }
        )
    }

    deletingCoverage?.let { view ->
        ConfirmDialog(
            title = "Delete ${view.coverage.kind.label}?",
            body = "It comes off the docket and out of the standing costs.",
            confirm = "Delete",
            onDismiss = { deletingCoverage = null },
            onConfirm = { deletingCoverage = null; vm.deleteCoverage(view.coverage.id) }
        )
    }
}

/** One loan, with everything the terms imply worked out. */
@Composable
private fun LoanCard(loan: LoanView, onEdit: () -> Unit, onDelete: () -> Unit) {
    val snapshot = loan.snapshot
    SectionCard(
        title = loan.label,
        trailing = { Text(money(snapshot.balanceCents, withCents = false), style = MaterialTheme.typography.titleMedium) }
    ) {
        Text(
            listOfNotNull(
                loan.lender,
                loan.accountRef?.let { "••••$it" },
                "${Loan.formatRate(loan.terms.annualRateBps)} · ${loan.terms.termMonths / 12} yr"
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LinearProgressIndicator(
            progress = { snapshot.progress(loan.terms.principalCents) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (snapshot.isPaidOff) {
                "Paid off"
            } else {
                "${snapshot.paymentsMade} of ${loan.terms.termMonths} payments · " +
                    (snapshot.paymentsRemaining?.let { "$it to go" } ?: "never clears at this payment")
            },
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            LabeledValue(
                label = "Monthly",
                value = money(loan.terms.totalMonthlyCents()),
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "Payoff",
                value = loan.payoffDate?.let { formatMonth(it) } ?: "—",
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "Equity",
                value = loan.equityCents?.let { money(it, withCents = false) } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
        if (loan.terms.escrowCents > 0L) {
            Text(
                "Includes ${money(loan.terms.escrowCents)} of escrow, which is not debt and is never amortised.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Interest paid so far: ${money(snapshot.interestPaidCents, withCents = false)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        loan.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** One policy, warranty or registration. */
@Composable
private fun CoverageCard(view: CoverageView, onEdit: () -> Unit, onDelete: () -> Unit) {
    val coverage = view.coverage
    SectionCard(title = "${coverage.kind.label} · ${coverage.provider}", trailing = { StatusPill(view.status) }) {
        Text(view.summary, style = MaterialTheme.typography.bodyMedium, color = statusColor(view.status))
        Text(
            listOfNotNull(
                coverage.policyNumber?.let { "Policy $it" },
                coverage.premiumCents.takeIf { it > 0L }?.let {
                    if (coverage.period == PremiumPeriod.ONE_TIME) money(it) else "${money(it)} ${coverage.period.label}"
                },
                coverage.expiresAt?.let { formatDay(it) }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        coverage.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** What it has cost: this year, all time, and — only when honest — per year and per mile. */
@Composable
private fun CostsCard(detail: AssetDetail) {
    SectionCard(title = "What it costs") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            LabeledValue(
                label = "Last 12 months",
                value = money(detail.costsThisYear.allInCents, withCents = false),
                modifier = Modifier.weight(1f)
            )
            LabeledValue(
                label = "All time",
                value = money(detail.costsAllTime.totalCents, withCents = false),
                modifier = Modifier.weight(1f)
            )
        }
        if (detail.costsThisYear.coverageCents > 0L) {
            Text(
                "Includes ${money(detail.costsThisYear.coverageCents, withCents = false)} of premiums.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            LabeledValue(
                label = "Per year",
                value = detail.perYearCents?.let { money(it, withCents = false) } ?: "Not a year of history yet",
                modifier = Modifier.weight(1f)
            )
            detail.meter?.let { meter ->
                LabeledValue(
                    label = "Per ${meter.unit.short}",
                    value = detail.centsPerMeterUnit?.let { cents -> money(Math.round(cents)) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (detail.records.isNotEmpty()) {
            Text(
                "${detail.costsAllTime.entries} jobs logged, most recently " +
                    "${detail.costsAllTime.lastAt?.let { formatDay(it) } ?: "—"}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
