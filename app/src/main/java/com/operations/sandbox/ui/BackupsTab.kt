@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.operations.backupkit.AppId

/**
 * Backing the whole suite up into one archive, restoring from one, and the scheduled upload
 * to your own storage container.
 */

@Composable
internal fun BackupsTab(
    backup: BackupController,
    cloud: CloudBackupController,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    // One list of ticks governs the whole tab — the zip, the restore, and the scheduled upload.
    // A second, invisible selection for the schedule is how somebody unticks Finance, takes a
    // backup without it, and keeps uploading it every night regardless.
    LaunchedEffect(backup.selected) { cloud.setIncludedApps(backup.selected) }

    SectionCard(
        title = "What to include",
        subtitle = "Everything on this tab acts on the apps ticked here — the zip, the restore, " +
            "and the scheduled upload."
    ) {
        backup.apps.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !backup.working) {
                        backup.setSelected(entry.appId, entry.appId !in backup.selected)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = entry.appId in backup.selected,
                    enabled = !backup.working,
                    onCheckedChange = { on -> backup.setSelected(entry.appId, on) }
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                        // LifeOps is the suite's standard app; flag it here as the hub always has.
                        if (entry.appId == AppId.LIFEOPS) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "STANDARD",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        "Backup format v${entry.dataVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    SectionCard(
        title = "Backup & restore",
        subtitle = "Full Backup writes the selected apps into a single .zip. Restore reads that same " +
            "zip back into the selected apps."
    ) {
        Button(
            onClick = onBackup,
            enabled = backup.selected.isNotEmpty() && !backup.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Full Backup (${backup.selected.size} selected)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestore,
            enabled = backup.selected.isNotEmpty() && !backup.working,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore from zip…")
        }
        if (backup.working) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        backup.status?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }

    CloudBackupSection(cloud, appCount = backup.apps.size)
}
