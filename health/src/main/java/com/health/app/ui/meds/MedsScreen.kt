package com.health.app.ui.meds

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.data.model.Profile
import com.health.app.logic.Cabinet
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DoseStatus
import com.health.app.logic.ExpiryStatus
import com.health.app.logic.ReminderMode
import com.health.app.logic.StockStatus
import com.health.app.ui.common.*

/**
 * The medicine cabinet.
 *
 * The tab answers two different questions and is split accordingly, because conflating them is what
 * made the old single list quietly wrong in a household:
 *
 *  - **Cabinet** — *what do we have?* The physical stock: every bottle and box, whether it is still
 *    in date, whether there is enough left, where it is, and — the part that makes it more than a
 *    shopping list — everyone who takes it, each with their own dose and their own live dose window.
 *    Household-scoped, because a bottle belongs to the house, not to a person.
 *  - **[Name]'s medicines** — *what does she take, and can she have some yet?* The per-person
 *    regimen, unchanged in substance from before: the label's own spacing and daily limits, and
 *    `logic/DoseSchedule`'s answer to the only question anyone asks at 3am.
 *
 * A medicine added by lookup carries its product's facts — ingredients, form, and the label's own
 * text from openFDA — and those are shown as the reference they are: the manufacturer's words,
 * attributed and dated, next to the dose rules *you* typed in. Health never computes a dose from a
 * label, and the disclaimer at the bottom of the screen means what it says.
 */
@Composable
fun MedsScreen(vm: MedsViewModel, onOpenPeople: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val doses by vm.doses.collectAsStateWithLifecycle()
    val cabinet by vm.cabinet.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showDose by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CabinetItem?>(null) }
    var restocking by remember { mutableStateOf<CabinetItem?>(null) }
    var reminderFor by remember { mutableStateOf<Medication?>(null) }
    var reading by remember { mutableStateOf<CabinetEntry?>(null) }

    if (profiles.isEmpty()) {
        NoProfiles(onOpenPeople)
        return
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Medicine") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ProfileBar(profiles, selected?.id, vm::select, onOpenPeople)
            HorizontalDivider()

            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Cabinet") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(selected?.name?.let { "$it's meds" } ?: "Medicines") }
                )
            }

            when (tab) {
                0 -> CabinetList(
                    entries = cabinet,
                    onGive = vm::give,
                    onRestock = { restocking = it },
                    onEdit = { editing = it },
                    onRead = { reading = it },
                    onDelete = { vm.deleteCabinetItem(it.id) },
                    onAdd = { showAdd = true }
                )
                else -> PersonMedicines(
                    profile = selected,
                    statuses = statuses,
                    doses = doses,
                    onGive = vm::give,
                    onToggleActive = { vm.setActive(it, !it.active) },
                    onReminder = { reminderFor = it },
                    onDelete = vm::deleteMedication,
                    onDeleteDose = vm::deleteDose,
                    onLogDose = { showDose = true }
                )
            }
        }
    }

    if (showAdd) {
        AddMedicineDialog(
            vm = vm,
            personName = selected?.name,
            cabinet = cabinet.map { it.item },
            onDismiss = {
                showAdd = false
                vm.clearSearch()
            }
        )
    }
    if (showDose) {
        LogDoseDialog(
            medications = statuses.map { it.medication }.filter { it.active },
            onDismiss = { showDose = false },
            onConfirm = { medication, name, amount, unit, note, at ->
                vm.logDose(medication, name, amount, unit, note, at)
                showDose = false
            }
        )
    }
    editing?.let { item ->
        CabinetItemDialog(
            item = item,
            onDismiss = { editing = null },
            onConfirm = {
                vm.updateCabinetItem(it)
                editing = null
            }
        )
    }
    restocking?.let { item ->
        RestockDialog(
            item = item,
            onDismiss = { restocking = null },
            onConfirm = { quantity, expiry ->
                vm.restock(item.id, quantity, expiry)
                restocking = null
            }
        )
    }
    reminderFor?.let { medication ->
        ReminderDialog(
            medication = medication,
            onDismiss = { reminderFor = null },
            onConfirm = { mode, times ->
                vm.setReminder(medication, mode, times)
                reminderFor = null
            }
        )
    }
    reading?.let { entry ->
        // Re-read the entry from the live flow, so a "look it up again" that succeeds updates the
        // sheet in place rather than leaving the reader looking at the answer they just replaced.
        val current = cabinet.firstOrNull { it.item.id == entry.item.id } ?: entry
        MonographSheet(
            entry = current,
            refreshing = search.fetchingRxcui != null && search.fetchingRxcui == current.item.rxcui,
            onRefresh = { current.item.rxcui?.let(vm::refreshMonograph) },
            onDismiss = { reading = null }
        )
    }
}

/** The household's stock, worst news first — see `logic/Cabinet` for what "worst" means. */
@Composable
private fun CabinetList(
    entries: List<CabinetEntry>,
    onGive: (Medication) -> Unit,
    onRestock: (CabinetItem) -> Unit,
    onEdit: (CabinetItem) -> Unit,
    onRead: (CabinetEntry) -> Unit,
    onDelete: (CabinetItem) -> Unit,
    onAdd: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                SectionCard(title = "The cabinet is empty") {
                    Text(
                        "Add what's actually in the house — the bottle of Calpol, the box of " +
                            "ibuprofen, the antihistamines nobody can find. Search for a medicine " +
                            "by name and Health fills in what it is and what its label says; the " +
                            "amount left, the expiry date and where it lives are yours to type.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onAdd) { Text("Add the first one") }
                }
            }
        } else {
            val flagged = entries.count { it.status.needsAttention }
            if (flagged > 0) {
                item(key = "attention") {
                    Text(
                        if (flagged == 1) {
                            "1 item needs attention — expired, out, or running low."
                        } else {
                            "$flagged items need attention — expired, out, or running low."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            items(entries, key = { it.item.id }) { entry ->
                CabinetCard(
                    entry = entry,
                    onGive = onGive,
                    onRestock = { onRestock(entry.item) },
                    onEdit = { onEdit(entry.item) },
                    onRead = { onRead(entry) },
                    onDelete = { onDelete(entry.item) }
                )
            }
        }

        item(key = "disclaimer") { DisclaimerText() }
    }
}

@Composable
private fun CabinetCard(
    entry: CabinetEntry,
    onGive: (Medication) -> Unit,
    onRestock: () -> Unit,
    onEdit: () -> Unit,
    onRead: () -> Unit,
    onDelete: () -> Unit
) {
    val item = entry.item
    val status = entry.status
    var confirmDelete by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                    listOfNotNull(
                        item.descriptor,
                        Cabinet.describeExpiry(item.expiryDate)?.let { "exp. $it" }
                    ).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                CabinetBadge(status.expiry, status.stock)
            }

            Text(
                status.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.needsAttention) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            entry.monograph?.let { monograph ->
                monograph.ingredients.takeIf { it.isNotEmpty() }?.let { ingredients ->
                    Text(
                        ingredients.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (entry.takenBy.isEmpty()) {
                Text(
                    "Nobody's medicines point at this yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider()
                // The part that makes this a cabinet rather than a shopping list: standing in front
                // of the bottle, the question is whose dose is what, and who can have some yet.
                entry.takenBy.forEach { use ->
                    CabinetUseRow(
                        profile = use.profile,
                        medication = use.medication,
                        readyNow = use.window.isReady && use.medication.active,
                        statusText = doseStatusText(use.window.status, use.window),
                        onGive = { onGive(use.medication) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRestock) { Text("Restock") }
                TextButton(onClick = onEdit) { Text("Edit") }
                if (entry.monograph?.hasLabel == true || item.rxcui != null) {
                    TextButton(onClick = onRead) { Text("Label") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Remove") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${item.name}?") },
            text = {
                Text(
                    "It comes out of the cabinet. Everyone's medicines and every dose already " +
                        "recorded stay exactly as they are — throwing the box away doesn't " +
                        "un-happen the doses."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        )
    }
}

/** One person's dose of one cabinet item, with the answer to "can they have some yet?". */
@Composable
private fun CabinetUseRow(
    profile: Profile,
    medication: Medication,
    readyNow: Boolean,
    statusText: String,
    onGive: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileDot(profile, size = 28, selected = true)
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    medication.doseAmount?.let { "${trimAmount(it)} ${medication.doseUnit}".trim() },
                    medication.minIntervalHours?.let { "every ${trimAmount(it)}h" },
                    statusText
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (readyNow) {
            Button(onClick = onGive, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Give") }
        }
    }
}

/** The selected person's own list — their doses, their limits, their reminders. */
@Composable
private fun PersonMedicines(
    profile: Profile?,
    statuses: List<MedicationStatus>,
    doses: List<com.health.app.data.model.Dose>,
    onGive: (Medication) -> Unit,
    onToggleActive: (Medication) -> Unit,
    onReminder: (Medication) -> Unit,
    onDelete: (Medication) -> Unit,
    onDeleteDose: (com.health.app.data.model.Dose) -> Unit,
    onLogDose: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (statuses.isEmpty()) {
            item(key = "empty") {
                SectionCard(title = "No medicines yet") {
                    Text(
                        "Add one with the spacing and daily limit printed on its label. Health " +
                            "then answers the only question that matters at 3am — whether the " +
                            "next dose is due — instead of leaving you to do the arithmetic. " +
                            "Search for it by name and its ingredients, form and label come with it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(statuses, key = { it.medication.id }) { status ->
                MedicationCard(
                    status = status,
                    onGive = { onGive(status.medication) },
                    onToggleActive = { onToggleActive(status.medication) },
                    onReminder = { onReminder(status.medication) },
                    onDelete = { onDelete(status.medication) }
                )
            }
        }

        item(key = "log-dose") {
            OutlinedButton(onClick = onLogDose, modifier = Modifier.fillMaxWidth()) {
                Text("Record a dose given")
            }
        }

        item(key = "history-header") {
            Text(
                profile?.let { "Doses given to ${it.name}" } ?: "Doses given",
                style = MaterialTheme.typography.titleSmall
            )
        }
        if (doses.isEmpty()) {
            item(key = "no-doses") {
                Text("Nothing given yet.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(doses, key = { it.id }) { dose ->
                RecordRow(
                    headline = dose.medicationName,
                    support = listOfNotNull(
                        "${trimAmount(dose.amount)} ${dose.unit}".trim(),
                        formatStamp(dose.takenAt),
                        dose.note
                    ).joinToString(" · "),
                    onDelete = { onDeleteDose(dose) }
                )
            }
        }

        item(key = "disclaimer") { DisclaimerText() }
    }
}

@Composable
private fun MedicationCard(
    status: MedicationStatus,
    onGive: () -> Unit,
    onToggleActive: () -> Unit,
    onReminder: () -> Unit,
    onDelete: () -> Unit
) {
    val medication = status.medication
    val window = status.window
    val now = System.currentTimeMillis()
    val statusColor = when (window.status) {
        DoseStatus.READY -> MaterialTheme.colorScheme.primary
        DoseStatus.WAIT -> MaterialTheme.colorScheme.onSurfaceVariant
        DoseStatus.LIMIT_REACHED -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(medication.name, style = MaterialTheme.typography.titleMedium)
                    listOfNotNull(
                        medication.strength,
                        medication.doseAmount?.let { "${trimAmount(it)} ${medication.doseUnit}" },
                        medication.minIntervalHours?.let { "every ${trimAmount(it)}h" },
                        medication.maxDosesPer24h?.let { "max $it/day" },
                        medication.maxAmountPer24h?.let { "max ${trimAmount(it)} ${medication.doseUnit}/day" }
                    ).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (window.isReady && medication.active) {
                    Button(onClick = onGive) { Text("Give") }
                } else {
                    Text(
                        when (window.status) {
                            DoseStatus.READY -> "Paused"
                            DoseStatus.WAIT -> DoseSchedule.formatDuration(window.waitMillis(now))
                            DoseStatus.LIMIT_REACHED -> "Limit"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                }
            }

            Text(window.reason, style = MaterialTheme.typography.bodySmall, color = statusColor)

            // What it is actually made of, when the product was looked up. This is the line that
            // catches the mistake households genuinely make: two brands, one ingredient, two doses.
            status.monograph?.ingredients?.takeIf { it.isNotEmpty() }?.let { ingredients ->
                Text(
                    "Contains ${ingredients.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            window.lastDoseAtMillis?.let {
                Text(
                    "Last given ${formatStamp(it)} (${DoseSchedule.formatAgo(now - it)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // The cabinet's answer, on the person's own card: whether the bottle this comes out of
            // can still cover the dose the card just quoted.
            status.cabinetItem?.let { item ->
                val cabinetStatus = status.cabinetStatus
                Text(
                    listOfNotNull("From ${item.name}", cabinetStatus?.summary).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cabinetStatus?.needsAttention == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (medication.reminderMode != ReminderMode.OFF) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (medication.reminderArmed) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        medication.reminderSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggleActive) {
                    Text(if (medication.active) "Pause" else "Resume")
                }
                TextButton(onClick = onReminder) { Text("Remind me") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** The one-word verdict on an item: what's wrong with it, or that nothing is. */
@Composable
private fun CabinetBadge(expiry: ExpiryStatus, stock: StockStatus) {
    val (label, color) = when {
        expiry == ExpiryStatus.EXPIRED -> "Expired" to MaterialTheme.colorScheme.error
        stock == StockStatus.OUT -> "Out" to MaterialTheme.colorScheme.error
        expiry == ExpiryStatus.EXPIRING_SOON -> "Expiring" to MaterialTheme.colorScheme.tertiary
        stock == StockStatus.LOW -> "Low" to MaterialTheme.colorScheme.tertiary
        else -> return
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** How a dose window reads in one phrase inside a cabinet row. */
private fun doseStatusText(
    status: DoseStatus,
    window: com.health.app.logic.DoseWindow,
    now: Long = System.currentTimeMillis()
): String = when (status) {
    DoseStatus.READY -> "due now"
    DoseStatus.WAIT -> "in ${DoseSchedule.formatDuration(window.waitMillis(now))}"
    DoseStatus.LIMIT_REACHED -> "daily limit reached"
}
