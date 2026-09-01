package com.maintenance.app.ui.asset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.model.RecallView
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.SchedulePack
import com.maintenance.app.ui.common.LabeledValue
import com.maintenance.app.ui.common.SectionCard
import com.maintenance.app.ui.common.formatDay
import com.maintenance.app.ui.common.statusColor
import com.maintenance.app.logic.DueStatus

/**
 * The two things a vehicle's VIN opens up: what it *is*, and what has been recalled on it.
 *
 * Both are offered rather than done. A decode fills in only the fields you left blank, a schedule is
 * chosen from the ones that match rather than applied on your behalf, and a recall is listed rather
 * than acted on — because everything here is a claim by somebody else about a model, and this is
 * your vehicle.
 */
@Composable
fun VehicleSection(vm: AssetDetailViewModel, detail: AssetDetail) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val asset = detail.asset
    val vin = asset.attribute("vin")

    SectionCard(title = "From the VIN") {
        if (vin.isNullOrBlank()) {
            Text(
                "Add the VIN and this can fill in the make, model, year, trim, body style, engine, " +
                    "fuel, transmission and drivetrain, offer the manufacturer's service schedule, " +
                    "and check for open safety recalls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        Text(
            "Only the first eleven characters are sent — the half that describes the model. The " +
                "serial stays on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.decodeVin(vin) }, enabled = !lookup.busy) {
                Text("Decode VIN")
            }
            val year = asset.year
            val make = asset.make
            val model = asset.model
            if (make != null && model != null && year != null) {
                OutlinedButton(
                    onClick = { vm.checkRecalls(make, model, year) },
                    enabled = !lookup.busy
                ) { Text("Check recalls") }
            }
            if (lookup.busy) CircularProgressIndicator(Modifier.size(18.dp))
        }

        lookup.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        detail.recallsCheckedAt?.let {
            Text(
                "Recalls last checked ${formatDay(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val facts = lookup.facts
    if (facts != null) {
        DecodeDialog(
            vm = vm,
            detail = detail,
            onDismiss = vm::dismissLookup
        )
    }
}

/** What came back, what it would fill in, and which schedules fit it. */
@Composable
private fun DecodeDialog(vm: AssetDetailViewModel, detail: AssetDetail, onDismiss: () -> Unit) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val facts = lookup.facts ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(facts.descriptor.ifBlank { "Decoded" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (facts.detail.isNotBlank()) {
                    Text(facts.detail, style = MaterialTheme.typography.bodyMedium)
                }
                facts.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                val applied = lookup.applied
                if (applied != null) {
                    Text(
                        "${applied.pack.label}: ${applied.summary}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (lookup.packs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Service schedules that fit", style = MaterialTheme.typography.labelLarge)
                    lookup.packs.forEach { pack -> PackRow(pack = pack, onApply = { vm.applyPack(pack) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.useFacts(facts); onDismiss() }) { Text("Use these details") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * One schedule on offer, with where its numbers came from.
 *
 * The source line is not decoration. A transcribed schedule is a claim about somebody's vehicle, and
 * the honest thing is to say where it came from and that nobody has checked it — an interval you can
 * see the provenance of is one you might correct, and every item becomes an ordinary editable plan
 * the moment it is applied.
 */
@Composable
private fun PackRow(pack: SchedulePack, onApply: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(pack.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "${pack.items.size} items · ${pack.duty.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (pack.provisional) "${pack.source} — not yet checked against a manual" else pack.source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onApply) { Text("Apply") }
    }
}

/**
 * The recalls on this vehicle.
 *
 * Acknowledging one is the only part of a recall this app owns: NHTSA says what is open for the
 * model, you say whether it has been dealt with on yours. It comes off the docket and stays on file
 * — the same distinction between finished and gone that the archive makes for an asset.
 */
@Composable
fun RecallsSection(vm: AssetDetailViewModel, detail: AssetDetail) {
    if (detail.asset.kind != AssetKind.VEHICLE || detail.recalls.isEmpty()) return

    SectionCard(
        title = "Safety recalls",
        trailing = {
            Text(
                if (detail.openRecalls == 0) "All dealt with" else "${detail.openRecalls} open",
                style = MaterialTheme.typography.labelMedium,
                color = if (detail.urgentRecalls > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        detail.recalls.forEach { view ->
            RecallRow(
                view = view,
                onToggle = { vm.setRecallAcknowledged(view.recall.campaignNumber, !view.acknowledged) }
            )
        }
    }
}

@Composable
private fun RecallRow(view: RecallView, onToggle: () -> Unit) {
    val recall = view.recall
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            recall.headline,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = when {
                view.acknowledged -> MaterialTheme.colorScheme.onSurfaceVariant
                recall.isUrgent -> statusColor(DueStatus.OVERDUE)
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text(recall.summary, style = MaterialTheme.typography.bodySmall)
        recall.remedy?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            LabeledValue(label = "Campaign", value = recall.campaignNumber, modifier = Modifier.weight(1f))
            LabeledValue(
                label = "Reported",
                value = recall.reportedOn?.toString() ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(onClick = onToggle) {
            Text(if (view.acknowledged) "Still open" else "Mark dealt with")
        }
    }
}
