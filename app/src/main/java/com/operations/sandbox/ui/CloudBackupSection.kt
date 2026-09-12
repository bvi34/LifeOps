package com.operations.sandbox.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.operations.backupkit.cloud.CloudBackupFrequency
import com.operations.backupkit.cloud.CloudBackupRetention

/**
 * The Backups tab's second half: send the same archive to an Azure storage account, on a schedule,
 * without anybody having to remember to.
 *
 * The local Full Backup above it is deliberate and manual — somebody picks a moment, picks a file,
 * and knows where the zip went. That is the right shape for a backup somebody takes and the wrong
 * shape for the one that saves them, because the archive that matters is the one taken on the
 * ordinary Tuesday nobody thought about it, and it has to be somewhere other than the phone.
 *
 * What the screen is trying to be honest about, in order: where it sends things, what the
 * credential it was given can do and how long for, when it last worked, and when it will next try.
 */
@Composable
internal fun CloudBackupSection(cloud: CloudBackupController, appCount: Int) {
    SectionCard(
        title = "Scheduled backup to Azure",
        subtitle = "The same archive as above, uploaded to a container in your own Azure storage " +
            "account on a schedule. Nothing is sent until you switch this on."
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Back up automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (cloud.enabled) {
                        "${cloud.frequency.label}, including ${cloud.includedSummary(appCount)}."
                    } else {
                        "Off — the sandbox never uploads anything on its own."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = cloud.enabled, onCheckedChange = { cloud.enabled = it })
        }

        Spacer(Modifier.height(16.dp))
        Text("Where it goes", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cloud.account,
            onValueChange = { cloud.account = it },
            label = { Text("Storage account") },
            supportingText = { Text("The name only — the `household` in household.blob.core.windows.net.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cloud.container,
            onValueChange = { cloud.container = it },
            label = { Text("Container") },
            supportingText = { Text("It has to exist already; this never creates one.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cloud.prefix,
            onValueChange = { cloud.prefix = it },
            label = { Text("Folder (optional)") },
            supportingText = { Text("A prefix inside the container, e.g. `phones/pixel`.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SasField(cloud)

        // The destination, or the first thing wrong with it — never both, and never a green tick
        // over an address nothing will reach.
        Spacer(Modifier.height(12.dp))
        val problem = cloud.problem
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Text(
                "Archives land in ${cloud.destination}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                cloud.signature,
                style = MaterialTheme.typography.bodySmall,
                color = if (cloud.signatureExpired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    SectionCard(
        title = "How often, and how many to keep",
        subtitle = "A whole-suite archive is not small. These two settings are what keep it off a " +
            "metered connection and out of an unbounded storage bill."
    ) {
        ChipRow(
            options = CloudBackupFrequency.entries,
            selected = cloud.frequency,
            label = { it.label },
            onSelect = { cloud.frequency = it }
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only on Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Waits for an unmetered network. Turn this off only if you know what an " +
                        "archive of everything costs on your plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = cloud.wifiOnly, onCheckedChange = { cloud.wifiOnly = it })
        }

        Spacer(Modifier.height(16.dp))
        Text(cloud.keepSummary(), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            options = CloudBackupRetention.KEEP_CHOICES,
            selected = cloud.keep,
            label = { if (it == CloudBackupRetention.KEEP_EVERYTHING) "Keep all" else "$it" },
            onSelect = { cloud.keep = it }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (cloud.keep == CloudBackupRetention.KEEP_EVERYTHING) {
                "Nothing is ever deleted from the container."
            } else if (cloud.canPrune) {
                "Older archives this app uploaded are deleted once there are more than " +
                    "${cloud.keep}. Nothing else in the container is ever touched."
            } else {
                "Deleting older archives needs a signature that can list and delete (`l` and `d`); " +
                    "with this one, nothing is deleted."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionCard(
        title = "Last run",
        subtitle = "\"Back up now\" takes the same archive, the same way, to the same place — so a " +
            "problem shows up here rather than at two in the morning."
    ) {
        Text(
            cloud.lastSuccess?.let { "Last archive uploaded $it." } ?: "No archive has been uploaded yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        cloud.nextRun?.let {
            Text(
                "Next one due $it, give or take — the phone picks a moment when it is on the right " +
                    "network and not low on battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { cloud.backUpNow() },
            enabled = !cloud.working && cloud.problem == null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (cloud.working) "Backing up…" else "Back up now")
        }
        if (cloud.working) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        cloud.status?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The signature field.
 *
 * Masked by default because it is a credential and this screen gets opened in front of people, and
 * revealable because it is also a long opaque string somebody has just pasted and may need to check
 * they pasted all of.
 */
@Composable
private fun SasField(cloud: CloudBackupController) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = cloud.sasToken,
        onValueChange = { cloud.sasToken = it },
        label = { Text("Shared access signature") },
        supportingText = {
            Text(
                "Paste the SAS token (or the whole URL) from the container's \"Generate SAS\" " +
                    "panel. Create and write permission is the minimum; add list and delete to let " +
                    "old archives be cleaned up."
            )
        },
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
        },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** One row of single-choice chips, scrollable so a narrow phone never clips the last option. */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}
