package com.health.app.ui.connect

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.app.connect.ConnectTypes
import com.health.app.connect.HealthConnectImporter
import com.health.app.data.model.ConnectRecord
import com.health.app.data.model.Profile
import com.health.app.logic.ConnectCategory
import com.health.app.logic.ConnectFormat
import com.health.app.logic.ConnectKind
import com.health.app.ui.common.ProfileDot
import com.health.app.ui.common.SectionCard
import com.health.app.ui.common.formatDayTime
import com.health.app.ui.common.formatStamp
import kotlinx.coroutines.launch

/**
 * Health Connect: whose data it is, whether Health may read it, and what has been read.
 *
 * The primary user comes first on the screen because it comes first in fact — nothing is imported
 * until it is chosen, and choosing the wrong person is the one mistake here that matters.
 */
@Composable
fun ConnectScreen(vm: ConnectViewModel, onOpenKind: (ConnectKind) -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val primary by vm.primary.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val lastResult by vm.lastResult.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val tempUnit by vm.tempUnit.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf<PrimarySwitch?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (vm.availability != HealthConnectImporter.Availability.AVAILABLE) {
        UnavailableCard(vm.availability)
        return
    }
    val askPermissions = rememberLauncherForActivityResult(remember { vm.permissionContract() }) {
        vm.onPermissionsResult()
    }

    // Grants change in Health Connect's own settings, outside this screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshGranted() }

    fun choose(profile: Profile) {
        val current = primary
        if (current == null || current.id == profile.id) {
            vm.setPrimary(profile, moveExisting = false)
            return
        }
        scope.launch {
            val count = vm.importedCountFor(current.id)
            if (count == 0) vm.setPrimary(profile, moveExisting = false)
            else switching = PrimarySwitch(current, profile, count)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(title = "Whose data is this?") {
                Text(
                    "Health Connect holds one person's data — whoever this phone belongs to. " +
                        "Everything Health reads from it is filed under the person chosen here.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (profiles.isEmpty()) {
                    Text(
                        "Nobody is in Health yet. Add the household in People first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { profile ->
                        val isPrimary = profile.id == primary?.id
                        FilterChip(
                            selected = isPrimary,
                            onClick = { choose(profile) },
                            label = { Text(profile.name) },
                            leadingIcon = { ProfileDot(profile, size = 24, selected = isPrimary) }
                        )
                    }
                }
                primary?.let {
                    Text(
                        "Primary user: ${it.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Health Connect",
                trailing = {
                    Switch(
                        checked = enabled,
                        enabled = primary != null,
                        onCheckedChange = { on ->
                            if (on) {
                                vm.turnOn()
                                if (granted.isEmpty()) askPermissions.launch(vm.requestable)
                            } else {
                                vm.turnOff()
                            }
                        }
                    )
                }
            ) {
                if (primary == null) {
                    Text("Choose the primary user above to switch the import on.", style = MaterialTheme.typography.bodyMedium)
                }
                val allowed = vm.allowedKinds(granted)
                val offered = vm.offeredKinds()
                Text(
                    "Allowed: $allowed of $offered kinds of data",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    buildString {
                        append(if (ConnectTypes.HISTORY in granted) "Full history" else "The last 30 days only")
                        append(" · ")
                        append(if (ConnectTypes.BACKGROUND in granted) "Imports in the background" else "Imports when Health is opened")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastSyncAt > 0 || lastResult != null) {
                    Text(
                        listOfNotNull(
                            lastSyncAt.takeIf { it > 0 }?.let { "Last import ${formatStamp(it)}" },
                            lastResult
                        ).joinToString(" — "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { askPermissions.launch(vm.requestable) }) { Text("Choose data") }
                    OutlinedButton(onClick = { vm.importNow() }, enabled = enabled && !importing) { Text("Import now") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Open Health Connect") }
                    TextButton(onClick = { confirmDisconnect = true }, enabled = granted.isNotEmpty()) { Text("Disconnect") }
                }
            }
        }

        if (today.isNotEmpty()) {
            item {
                SectionCard(title = "Today${primary?.let { " — ${it.name}" }.orEmpty()}") {
                    today.forEach { figure ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(figure.kind.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                ConnectFormat.value(figure.kind, figure.value, figure.secondaryValue, weightUnit, tempUnit),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        val byCategory = totals.groupBy { it.kind.category }
        ConnectCategory.entries.filter { it in byCategory }.forEach { category ->
            item {
                SectionCard(title = category.label) {
                    byCategory.getValue(category).forEach { total ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenKind(total.kind) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(total.kind.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${"%,d".format(total.count)} records" +
                                    (total.latestAt?.let { " · latest ${formatStamp(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (totals.isNotEmpty()) {
            item {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete everything imported") }
            }
        }
    }

    switching?.let { change ->
        AlertDialog(
            onDismissRequest = { switching = null },
            title = { Text("Make ${change.to.name} the primary user?") },
            text = {
                Text(
                    "${"%,d".format(change.count)} records from Health Connect are filed under " +
                        "${change.from.name}. If they were really ${change.to.name}'s all along, move " +
                        "them. If this phone has changed hands, leave them where they are — only new " +
                        "imports go to ${change.to.name}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setPrimary(change.to, moveExisting = true)
                    switching = null
                }) { Text("Move them") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { switching = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        vm.setPrimary(change.to, moveExisting = false)
                        switching = null
                    }) { Text("Leave with ${change.from.name}") }
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything imported?") },
            text = {
                Text(
                    "Every record read from Health Connect is removed from Health, along with the " +
                        "readings mirrored from them. Nothing typed into Health by hand is touched, " +
                        "and nothing in Health Connect itself is. If the import is still on, the next " +
                        "one reads it all again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteImported()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from Health Connect?") },
            text = {
                Text(
                    "Health gives back every permission Health Connect granted it and stops " +
                        "importing. What has already been imported stays in Health until you delete it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.disconnect()
                    confirmDisconnect = false
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
        )
    }
}

/** A change of primary user waiting on the household's answer about what was already imported. */
private data class PrimarySwitch(val from: Profile, val to: Profile, val count: Int)

/** Shown instead of the screen on a phone where Health Connect can't be used. */
@Composable
private fun UnavailableCard(availability: HealthConnectImporter.Availability) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Health Connect isn't available", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (availability == HealthConnectImporter.Availability.NEEDS_UPDATE) {
                "Health Connect needs updating before Health can read from it. Update it from the " +
                    "Play Store or system updates, then come back."
            } else {
                "This phone doesn't offer Health Connect, so there is nothing for Health to read. " +
                    "Everything recorded in Health by hand works as before."
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * One kind's imported records for the primary user, newest first — the two hundred most recent.
 */
@Composable
fun ConnectKindScreen(vm: ConnectViewModel, kind: ConnectKind) {
    val records by remember(kind) { vm.records(kind) }.collectAsStateWithLifecycle()
    val tempUnit by vm.tempUnit.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(kind.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
        }
        if (records.isEmpty()) {
            item { Text("Nothing imported of this kind.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(records, key = { it.id }) { record ->
            ConnectRecordRow(record, ConnectFormat.value(kind, record.value, record.secondaryValue, weightUnit, tempUnit))
            HorizontalDivider()
        }
    }
}

@Composable
private fun ConnectRecordRow(record: ConnectRecord, formatted: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val headline = ConnectDetail.headline(record)
            Text(headline ?: formatted, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(formatDayTime(record.startAt))
                    record.endAt?.let { end -> if (end != record.startAt) append(" – ${formatDayTime(end)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ConnectDetail.facts(record)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOfNotNull(record.device, record.source).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ConnectDetail.headline(record) != null && record.value != null) {
            Spacer(Modifier.width(8.dp))
            Text(formatted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
