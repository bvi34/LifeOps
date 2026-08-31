package com.people.app.ui.partner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.people.app.data.model.PartnerLink
import com.people.app.data.model.PartnerLinkState
import com.people.app.ui.common.SectionCard
import com.people.app.ui.common.formatDayTime

/**
 * What the person screen shows for a pairing, and the two dialogs behind it.
 *
 * The screen's job is to make a two-way handshake legible, because that is the part people get
 * wrong. Pairing is not one action: you show your code, they scan it, they show theirs, you scan it.
 * Halfway through, everything looks broken — you have a link and no data — so each state says which
 * half is outstanding and whose turn it is, rather than showing an empty week and a spinner.
 */

/** Everything the section needs, resolved. */
data class PartnerSectionState(
    val personName: String,
    val link: PartnerLink?,
    /** Our own code for this person, or null until it has been asked for. */
    val myCode: String?,
    /** The name a partner sees on our code. Empty until somebody sets it. */
    val myDisplayName: String,
    val message: String? = null,
    /** A round is in flight — the sync button is the one control it disables. */
    val syncing: Boolean = false
)

@Composable
fun PartnerSection(
    state: PartnerSectionState,
    onShowCode: () -> Unit,
    onSyncNow: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onCodeScanned: (String) -> Unit,
    onOpenWeek: () -> Unit,
    onUnlink: () -> Unit,
    onReset: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var showCode by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        // A null payload is a cancelled scan — somebody backed out of the camera, which is not an
        // error and deserves no message.
        result.contents?.let(onCodeScanned)
    }

    fun scan() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point the camera at ${state.personName}'s pairing code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    SectionCard(
        title = "Partner sync",
        trailing = {
            state.link?.let { link ->
                if (link.unseenChanges > 0) Badge { Text("${link.unseenChanges}") }
            }
        }
    ) {
        val link = state.link

        if (link == null) {
            Text(
                "Pair with ${state.personName}'s LifeOps and you can see each other's week. " +
                    "You each scan the other's code — one scan is not enough.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Their week is shown here, on its own screen. It never joins your own week, your " +
                    "aspects or your planning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            when (link.state) {
                PartnerLinkState.AWAITING_SCAN -> Text(
                    "Show ${state.personName} your code, and scan theirs. Until both scans are " +
                        "done, nothing is shared either way.",
                    style = MaterialTheme.typography.bodyMedium
                )

                PartnerLinkState.AWAITING_THEM -> Text(
                    "You have scanned ${link.partnerName}'s code. They need to scan yours before " +
                        "either of you sees anything — show them your code.",
                    style = MaterialTheme.typography.bodyMedium
                )

                PartnerLinkState.LINKED -> {
                    Text(
                        "Connected to ${link.partnerName}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (link.unseenChanges > 0) {
                            "${link.unseenChanges} change" +
                                (if (link.unseenChanges == 1) "" else "s") +
                                " since you last looked."
                        } else {
                            "Nothing new since you last looked."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            link.lastRejection?.let { rejection ->
                Text(
                    explain(rejection, link.partnerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            link.lastSyncAt?.let { at ->
                Text(
                    "Last synced ${formatDayTime(at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) { Text("OK") }
            }
        }

        HorizontalDivider()

        if (link?.state == PartnerLinkState.LINKED) {
            Button(onClick = onOpenWeek, modifier = Modifier.fillMaxWidth()) {
                Text("View LifeOps")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onShowCode()
                    showCode = true
                }
            ) { Text("My code") }
            OutlinedButton(onClick = { scan() }) { Text("Scan theirs") }
            TextButton(onClick = { showManualEntry = true }) { Text("Enter code") }
        }

        // Its own row, and present whether or not a pairing exists yet.
        //
        // Halfway through a handshake is exactly when somebody needs it: they have scanned your code
        // in the last ten seconds and the only other way to find out is to leave the app and come
        // back, since that is the one moment a round otherwise runs.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onSyncNow, enabled = !state.syncing) { Text("Sync now") }
            if (state.syncing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        if (link != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReset) { Text("Start pairing again") }
                TextButton(onClick = { confirmUnlink = true }) { Text("Unlink") }
            }
        }
    }

    if (showCode) {
        MyCodeDialog(
            personName = state.personName,
            code = state.myCode,
            displayName = state.myDisplayName,
            onDisplayNameChange = onDisplayNameChange,
            onDismiss = { showCode = false }
        )
    }
    if (showManualEntry) {
        EnterCodeDialog(
            onDismiss = { showManualEntry = false },
            onConfirm = {
                onCodeScanned(it)
                showManualEntry = false
            }
        )
    }
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink ${state.link?.partnerName.orEmpty()}?") },
            text = {
                Text(
                    "Their week is removed from this app and yours stops being published to them. " +
                        "Nothing on your own LifeOps week changes — including tasks they added to it."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnlink()
                        confirmUnlink = false
                    }
                ) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Our code, for them to scan.
 *
 * The name field is in this dialog rather than in a settings screen because this is the one moment
 * it matters: it is what the other person will see beside a week of tasks, and asking for it here —
 * with the code they are about to hand over — is the only time anybody has a reason to care.
 */
@Composable
private fun MyCodeDialog(
    personName: String,
    code: String?,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your pairing code") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("Your name, as $personName will see it") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (code != null) {
                    QrCode(code)
                    Text(
                        "Have $personName scan this. Then scan theirs — a connection needs both.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    AssistChip(
                        onClick = { clipboard.setText(AnnotatedString(code)) },
                        label = { Text("Copy code as text") }
                    )
                    Text(
                        "Pairing shares your whole current week with them, and theirs with you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Preparing your code…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/**
 * The typed-in fallback.
 *
 * Worth having for the same reason every pairing flow has one: cameras fail, lenses are cracked, a
 * code arrives in a message rather than on a screen in the same room. The payload is the identical
 * string the QR code carries, so the two routes cannot drift apart.
 */
@Composable
private fun EnterCodeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter their pairing code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Pairing code") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The same code their QR shows — they can copy it as text and send it to you.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Turn a round's refusal into something a person can act on.
 *
 * Each of these looks the same from the outside — an empty week — and each needs a different thing
 * done about it, which is why the reason is carried all the way from the engine to here rather than
 * collapsed into "sync failed".
 */
private fun explain(rejection: String, partnerName: String): String = when (rejection) {
    "NOTHING_PUBLISHED" -> "Nothing from $partnerName has arrived yet."
    "NOT_ADDRESSED_TO_US" -> "$partnerName hasn't scanned your code yet — show it to them."
    "TOKEN_MISMATCH" -> "This pairing no longer matches. Start pairing again and swap codes."
    "WRONG_INSTANCE" -> "The data arriving under $partnerName's name came from a different device."
    "DIFFERENT_WEEK" -> "$partnerName's app is still on a different week — it will catch up when they open it."
    else -> "Last sync didn't complete."
}
