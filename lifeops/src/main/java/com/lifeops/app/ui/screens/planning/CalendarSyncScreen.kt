@file:OptIn(ExperimentalMaterial3Api::class)

package com.lifeops.app.ui.screens.planning

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeops.app.ui.components.AppHeader
import com.lifeops.app.ui.components.BackNavIcon
import com.lifeops.app.util.DateUtil

/**
 * Two-way sync between the Planning calendar and a calendar on the device (see
 * GoogleCalendarSyncRepository) — grant calendar access, pick which calendar, sync on demand or
 * automatically. People tagged on an event round-trip as #tags.
 */
@Composable
fun CalendarSyncScreen(viewModel: CalendarSyncViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    Scaffold(topBar = { AppHeader(navigationIcon = { BackNavIcon(onBack) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Text(
                "Google Calendar Sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Syncs your own schedule with a calendar on this device — the one the Google " +
                    "Calendar app keeps up to date with your account. People tagged on an event " +
                    "carry over as #tags in the description.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            if (!state.hasPermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Calendar access needed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "LifeOps needs permission to read and write your device's calendars.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        Button(onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            )
                        }) { Text("Grant permission") }
                    }
                }
                return@Column
            }

            Text("Calendar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (state.calendars.isEmpty()) {
                Text(
                    "No calendars found on this device. Add a Google account in Android Settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    state.calendars.forEach { cal ->
                        val selected = cal.id == state.selectedCalendarId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, onClick = { viewModel.selectCalendar(cal) })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = { viewModel.selectCalendar(cal) })
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(cal.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    cal.accountName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Runs in the background every few hours",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = { viewModel.setSyncEnabled(it) },
                    enabled = state.selectedCalendarId != null
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.syncNow() },
                enabled = state.selectedCalendarId != null && !state.isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isSyncing) "Syncing…" else "Sync now")
            }

            state.lastSyncedAt?.let {
                Text(
                    "Last synced " + DateUtil.formatInstant(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            state.lastResult?.let { result ->
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (result.error == null) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Pushed ${result.pushed}, pulled ${result.pulled}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            result.error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
