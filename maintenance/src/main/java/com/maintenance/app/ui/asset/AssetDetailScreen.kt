package com.maintenance.app.ui.asset

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Handover
import com.maintenance.app.logic.HandoverRow
import com.maintenance.app.logic.AttributeCheck
import com.maintenance.app.logic.MeterUnit
import com.maintenance.app.logic.Vin
import com.maintenance.app.ui.common.AssetMark
import com.maintenance.app.ui.common.EmptyState
import com.maintenance.app.ui.common.LabeledValue
import com.maintenance.app.ui.common.SectionCard
import com.maintenance.app.ui.common.formatDay
import com.maintenance.app.ui.common.money
import java.time.LocalDate
import kotlinx.coroutines.launch

/** The four ways of looking at one asset. */
enum class AssetTab(val label: String) {
    OVERVIEW("Overview"),
    UPKEEP("Upkeep"),
    HISTORY("History"),
    MONEY("Money")
}

/**
 * One asset, in four tabs.
 *
 * They are four views of one thing rather than four screens: what it *is* (the VIN, the parcel
 * number, the serial), what it *needs* (the schedules), what has been *done* to it (the log), and
 * what it *costs* (the loan, the policies, the running total). Splitting those across a navigation
 * graph would mean four back-presses to answer "when did I last do this and what did it cost".
 *
 * The top bar carries the destructive things behind an overflow, and archiving above deleting —
 * because for an asset you have sold, archiving is almost always what was actually meant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    vm: AssetDetailViewModel,
    onBack: () -> Unit
) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(AssetTab.OVERVIEW.name) }
    val tab = AssetTab.entries.firstOrNull { it.name == tabName } ?: AssetTab.OVERVIEW

    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val current = detail

    // Handing the history to somebody else — see `logic/Handover`. The document is written where
    // the person picking says, through the system picker, so this needs no storage permission, no
    // FileProvider and no folder of its own: the file leaves the app and stops being its business.
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pending
        pending = null
        // A cancelled picker is not a failure and says nothing; a failed write is not allowed to
        // look like a success.
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        val wrote = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                ?: error("no stream")
        }.isSuccess
        scope.launch {
            snackbar.showSnackbar(if (wrote) "History saved." else "That didn't save. Try somewhere else.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            current?.asset?.name ?: "Asset",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        current?.asset?.let { asset ->
                            val line = asset.descriptor.ifBlank { asset.kind.label }
                            Text(
                                if (asset.archived) "$line · no longer owned" else line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit details") },
                            onClick = { menuOpen = false; editing = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Export service history") },
                            onClick = {
                                menuOpen = false
                                current?.let { detail ->
                                    pending = Handover.csv(
                                        rows = detail.records.map { record ->
                                            HandoverRow(
                                                performedAt = record.performedAt,
                                                title = record.title,
                                                vendor = record.vendor,
                                                meterValue = record.meterValue,
                                                costCents = record.costCents,
                                                notes = record.notes
                                            )
                                        },
                                        meterUnit = detail.meter?.unit
                                    )
                                    export.launch(
                                        Handover.fileName(
                                            detail.asset.descriptor.ifBlank { detail.asset.name },
                                            LocalDate.now()
                                        )
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (current?.asset?.archived == true) "Own it again" else "No longer own it") },
                            onClick = {
                                menuOpen = false
                                vm.setArchived(current?.asset?.archived != true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; confirmingDelete = true }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (current == null) {
            EmptyState(
                headline = "Gone",
                detail = "This asset is no longer here.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                AssetTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { tabName = entry.name },
                        text = { Text(entry.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            when (tab) {
                AssetTab.OVERVIEW -> OverviewTab(vm = vm, detail = current, onEdit = { editing = true })
                AssetTab.UPKEEP -> UpkeepTab(vm = vm, detail = current)
                AssetTab.HISTORY -> HistoryTab(vm = vm, detail = current)
                AssetTab.MONEY -> MoneyTab(vm = vm, detail = current)
            }
        }
    }

    if (editing && current != null) {
        EditAssetDialog(
            asset = current.asset,
            onDismiss = { editing = false },
            onSave = { asset, attributes ->
                editing = false
                vm.save(asset, attributes)
            }
        )
    }

    if (confirmingDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${current.asset.name}?") },
            text = {
                Text(
                    "Its schedules, its service history, its loans and its policies go with it. " +
                        "If you have only stopped owning it, mark it as no longer owned instead — the history is worth keeping."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; vm.delete(onBack) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } }
        )
    }
}

/**
 * What the thing *is*: its identity fields, what it cost, what it is worth, and whatever you wrote
 * about it.
 *
 * The VIN gets two extra lines nothing else gets — the model year it encodes, and a note when its
 * check digit disagrees — because a VIN is the one field here that can be checked without asking
 * anyone, and seventeen characters copied off a door jamb is exactly where a typo goes unnoticed
 * for four years.
 */
@Composable
private fun OverviewTab(vm: AssetDetailViewModel, detail: AssetDetail, onEdit: () -> Unit) {
    val asset = detail.asset

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "identity") {
            SectionCard(
                title = "${asset.kind.label} details",
                trailing = { TextButton(onClick = onEdit) { Text("Edit") } }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetMark(kind = asset.kind, colorArgb = asset.colorArgb, archived = asset.archived, size = 52)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(asset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (asset.descriptor.isNotBlank()) {
                            Text(asset.descriptor, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                val filled = asset.kind.attributes.filter { !asset.attribute(it.key).isNullOrBlank() }
                if (filled.isEmpty()) {
                    Text(
                        "Nothing recorded yet — the VIN, the serial number, the parcel number all live here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    filled.forEach { spec ->
                        val value = asset.attribute(spec.key).orEmpty()
                        LabeledValue(label = spec.label, value = value)
                        if (spec.check == AttributeCheck.VIN) {
                            VinFootnotes(value)
                        }
                    }
                }
            }
        }

        item(key = "value") {
            SectionCard(title = "Bought and worth") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LabeledValue(
                        label = "Bought",
                        value = asset.purchasedAt?.let { formatDay(it) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Paid",
                        value = asset.purchasePriceCents?.let { money(it, withCents = false) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    LabeledValue(
                        label = "Worth now",
                        value = asset.currentValueCents?.let { money(it, withCents = false) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "\"Worth now\" is whatever you last looked up. Nothing here guesses it, and nothing " +
                        "updates it on its own — it exists so equity against a loan can be shown at all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        detail.meter?.let { meter ->
            item(key = "meter") {
                SectionCard(title = meter.unit.reading) {
                    LabeledValue(
                        label = "Latest",
                        value = meter.current?.let { meter.unit.format(it) } ?: "No reading yet"
                    )
                    LabeledValue(
                        label = "Rate",
                        value = meter.perDay?.let { rate ->
                            "${perYear(rate)} ${meter.unit.noun} a year"
                        } ?: "Two readings will give a rate — and turn a mileage interval into a date."
                    )
                }
            }
        }

        val notes = asset.notes
        if (asset.kind == AssetKind.VEHICLE) {
            item(key = "vin-lookup") { VehicleSection(vm = vm, detail = detail) }
            if (detail.recalls.isNotEmpty()) {
                item(key = "recalls") { RecallsSection(vm = vm, detail = detail) }
            }
        }

        if (!notes.isNullOrBlank()) {
            item(key = "notes") {
                SectionCard(title = "Notes") { Text(notes, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }
}

/** The two things a VIN can say for itself, shown under it and never written into the year field. */
@Composable
private fun VinFootnotes(vin: String) {
    val problem = Vin.problem(vin)
    val modelYear = Vin.modelYear(vin, LocalDate.now().year)

    if (modelYear != null) {
        Text(
            "$modelYear model year, by the VIN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (problem != null) {
        Text(
            problem.message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** Rates are computed per day and read per year; nobody thinks in miles a day. */
private fun perYear(perDay: Double): String = MeterUnit.group(Math.round(perDay * 365.0))
