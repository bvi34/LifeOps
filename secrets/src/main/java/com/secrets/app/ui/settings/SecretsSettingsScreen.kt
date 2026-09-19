package com.secrets.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import com.operations.suite.ui.fields.SuiteTextField
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The settings, and the one screen in this app that does something no other app in the suite does:
 * **finish a restore**.
 *
 * Everything else here is a preference — how long before the vault shuts, whether the clipboard is
 * cleared, whether the device lock can stand in for the passphrase — or a door to somewhere else,
 * which is what the import card is: moving in from a browser or from 1Password is a screen of its
 * own (see `ui/importer/ImportScreen`), and it starts here because this is where somebody looks for
 * it on the day they install this app.
 *
 * The merge card is different in kind. When the sandbox restores an archive onto a phone that
 * already has a vault, the archived one is not applied: it is left beside the live one, because a
 * wholesale swap would delete every password added since the backup and there is nowhere to fetch
 * those back from. It waits here until somebody unlocks it — with *its* passphrase, which may be an older one — and then the two are
 * merged item by item, newest wins, tombstones respected.
 *
 * That is the whole reason the restore of a vault is a conversation rather than a file copy.
 */
@Composable
fun SecretsSettingsScreen(store: VaultStore, prefs: SecretsPrefs, onImport: () -> Unit) {
    val scope = rememberCoroutineScope()

    var autoLock by remember { mutableStateOf(prefs.autoLockMinutes.toFloat()) }
    var lockOnLeave by remember { mutableStateOf(prefs.lockOnLeave) }
    var clipboardSeconds by remember { mutableStateOf(prefs.clipboardClearSeconds.toFloat()) }
    var deviceUnlock by remember { mutableStateOf(store.device.isEnabled) }
    var staged by remember { mutableStateOf(store.stagedRestores) }
    var mergeTarget by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var changingPassphrase by remember { mutableStateOf(false) }
    var destroying by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        message?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (staged.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("A vault came back in a backup", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "It was not applied on top of this one, because that would have thrown " +
                            "away anything saved since the backup was taken. Merge it and both are " +
                            "kept: where the same item exists in each, the newer one wins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    staged.forEach { file ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = DateFormat.getDateTimeInstance().format(Date(file.lastModified())),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${file.length()} bytes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = { mergeTarget = file }) { Text("Merge") }
                            TextButton(onClick = {
                                store.discardStaged(file)
                                staged = store.stagedRestores
                            }) { Text("Discard") }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Bring passwords in", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "From the file a browser or 1Password gives you when you ask for your " +
                        "passwords back. Read on this phone, from a file you already have — this " +
                        "app cannot reach the network, so there is no other way it could be done " +
                        "and no other way it will be.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImport) { Text("Import from a file") }
            }
        }

        SettingsCard(
            title = "Lock after",
            body = if (autoLock.toInt() == 0) {
                "Never. The vault stays open until you close it or the app is killed."
            } else {
                "${autoLock.toInt()} minutes of doing nothing."
            }
        ) {
            Slider(
                value = autoLock,
                onValueChange = {
                    autoLock = it
                    prefs.autoLockMinutes = it.toInt()
                },
                valueRange = 0f..30f,
                steps = 29
            )
        }

        SettingsCard(
            title = "Lock when leaving",
            body = "Shut the vault the moment this app goes to the background. Safer, and it makes " +
                "copy-here-paste-there a four-step dance, which is why it is off by default."
        ) {
            Switch(checked = lockOnLeave, onCheckedChange = {
                lockOnLeave = it
                prefs.lockOnLeave = it
            })
        }

        SettingsCard(
            title = "Clear the clipboard",
            body = if (clipboardSeconds.toInt() == 0) {
                "Never — a copied password stays in the clipboard until something else replaces it."
            } else {
                "${clipboardSeconds.toInt()} seconds after copying, if nothing else has been copied since."
            }
        ) {
            Slider(
                value = clipboardSeconds,
                onValueChange = {
                    clipboardSeconds = it
                    prefs.clipboardClearSeconds = it.toInt()
                },
                valueRange = 0f..300f,
                steps = 9
            )
        }

        AutofillCard()

        PasskeyCard()

        SettingsCard(
            title = "Open with the device lock",
            body = if (store.device.canOffer) {
                "Use your fingerprint or screen lock instead of typing the passphrase. The key is " +
                    "wrapped by this phone's keystore, never leaves it, and is not in the backup — " +
                    "so it is a shortcut on this phone, not a way into the vault anywhere else."
            } else {
                "Not available: this phone has no screen lock, so there would be nothing standing " +
                    "between the vault and whoever is holding it."
            }
        ) {
            Switch(
                checked = deviceUnlock,
                enabled = store.device.canOffer,
                onCheckedChange = { wanted ->
                    deviceUnlock = if (wanted) store.enableDeviceUnlock() else {
                        store.device.disable()
                        false
                    }
                    message = if (wanted && !deviceUnlock) "The vault has to be open to set this up." else null
                }
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Master passphrase", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Changing it re-wraps the vault key — nothing inside is re-encrypted, and " +
                        "the device shortcut keeps working. Backups taken before the change still " +
                        "need the old passphrase, because that is what they were sealed with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { changingPassphrase = true }) { Text("Change it") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Delete the vault", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Everything in it, and the device shortcut with it. There is no recovery: " +
                        "no reset link, no support address, no copy anybody else holds. This is the " +
                        "only way past a forgotten passphrase and it costs you the contents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { destroying = true }) { Text("Delete everything") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    mergeTarget?.let { file ->
        PassphrasePrompt(
            title = "Unlock the restored vault",
            body = "Type the passphrase that vault was sealed with. If it was taken before you " +
                "changed your passphrase, that is the older one.",
            confirmLabel = "Merge",
            onDismiss = { mergeTarget = null },
            onConfirm = { passphrase ->
                mergeTarget = null
                scope.launch {
                    val chars = passphrase.toCharArray()
                    val outcome = store.mergeStaged(file, chars)
                    chars.fill(' ')
                    message = if (outcome == null) {
                        "That passphrase does not open it, or the file is not readable."
                    } else {
                        store.discardStaged(file)
                        staged = store.stagedRestores
                        "Merged: ${outcome.added} added, ${outcome.updated} updated, " +
                            "${outcome.deleted} deleted, ${outcome.unchanged} already the same."
                    }
                }
            }
        )
    }

    if (changingPassphrase) {
        ChangePassphraseDialog(
            onDismiss = { changingPassphrase = false },
            onConfirm = { current, replacement ->
                changingPassphrase = false
                scope.launch {
                    val currentChars = current.toCharArray()
                    val newChars = replacement.toCharArray()
                    val ok = store.changePassphrase(currentChars, newChars)
                    currentChars.fill(' ')
                    newChars.fill(' ')
                    message = if (ok) {
                        "Changed. Older backups still open with the passphrase they were sealed with."
                    } else {
                        "That is not the current passphrase."
                    }
                }
            }
        )
    }

    if (destroying) {
        AlertDialog(
            onDismissRequest = { destroying = false },
            title = { Text("Delete the vault?") },
            text = {
                Text(
                    "Every password in it goes, and every credential the other apps kept here goes " +
                        "with it. They will fall back to their own stores, which means the next " +
                        "restore loses them. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    destroying = false
                    scope.launch {
                        store.destroy()
                        message = "The vault is gone."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { destroying = false }) { Text("Keep it") } }
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    body: String,
    control: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            control()
        }
    }
}

@Composable
private fun PassphrasePrompt(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                SuiteTextField(
                    label = "Passphrase",
                    value = value,
                    onValueChange = { value = it },
                    capitalise = KeyboardCapitalization.None
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChangePassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change the passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuiteTextField(
                    label = "Current",
                    value = current,
                    onValueChange = { current = it },
                    capitalise = KeyboardCapitalization.None
                )
                SuiteTextField(
                    label = "New",
                    value = replacement,
                    onValueChange = { replacement = it },
                    capitalise = KeyboardCapitalization.None
                )
                SuiteTextField(
                    label = "New again",
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    capitalise = KeyboardCapitalization.None,
                    isError = confirmation.isNotEmpty() && confirmation != replacement
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(current, replacement) },
                enabled = current.isNotEmpty() && replacement.length >= 12 && replacement == confirmation
            ) { Text("Change it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Turning on autofill, and being straight about what it means.
 *
 * The switch is not this app's to flip. Android makes the autofill service a system-wide choice,
 * made in system settings, of which there is exactly one at a time — so this card opens that screen
 * and reports back rather than pretending to own the setting. That is a better arrangement than the
 * alternative: a password manager that could appoint itself the thing which sees every form on the
 * phone would be a password manager worth being nervous about.
 *
 * What the card has to say, it says here rather than in a help page nobody opens. Autofill is the
 * one part of this app that runs when the app is not on screen, and the one that talks to software
 * the household did not choose, so the two limits on it belong next to the button that enables it:
 * the vault still has to be unlocked, and a credential is only ever offered to an app or a page the
 * item's own address matches.
 */
@Composable
private fun AutofillCard() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AutofillManager::class.java) }

    // Read on every recomposition rather than remembered: the household leaves for system settings
    // and comes back, and a cached answer would still say "off" on the screen they came back to.
    val supported = manager?.isAutofillSupported() == true
    val enabled = manager?.hasEnabledAutofillServices() == true

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Fill passwords in other apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !supported -> "This phone does not offer autofill, so there is nothing to turn on."
                    enabled -> "On. Secrets offers a sign-in when an app or a page asks for one — " +
                        "and only when the item's own address matches what is asking. The vault " +
                        "still has to be unlocked; a locked one offers a way in rather than an answer."
                    else -> "Off. Android picks one autofill service for the whole phone, in its " +
                        "own settings, so this opens that screen rather than deciding for you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (supported) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Nothing is offered to an app or page nothing is filed under. Where that " +
                        "happens you can still open this list and pick one yourself — which is a " +
                        "choice you made rather than one made for you.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(8.dp))
                if (enabled) {
                    OutlinedButton(onClick = { manager?.disableAutofillServices() }) {
                        Text("Turn it off")
                    }
                } else {
                    OutlinedButton(onClick = {
                        // ACTION_REQUEST_SET_AUTOFILL_SERVICE needs the package as its data, and
                        // is not present on every build — a phone whose vendor removed the screen
                        // answers `resolveActivity` with null rather than crashing the vault.
                        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                            .setData(Uri.parse("package:" + context.packageName))
                        runCatching { context.startActivity(intent) }
                    }) { Text("Choose Secrets in system settings") }
                }
            }
        }
    }
}

/**
 * Turning Secrets into a passkey provider.
 *
 * Like autofill, the choice is not this app's to make — Android keeps it in its own settings, which
 * is the right arrangement for the same reason: an app that could appoint itself the holder of your
 * sign-ins would be an app worth being nervous about. Unlike autofill, the state is not reported
 * back here. The platform does expose a way to ask, and it is not one this app can check honestly
 * on every phone, so the card says where the switch lives rather than claiming to know which way it
 * is set.
 *
 * The Android 14 floor is stated rather than hidden behind a disabled control. A third-party app can
 * hold passkeys *only* through Credential Manager's provider API, which does not exist before 14 —
 * so on an older phone there is no version of this feature to offer, and a greyed-out switch would
 * imply there was one behind some other obstacle.
 */
@Composable
private fun PasskeyCard() {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Keep passkeys here", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A passkey signs you in with no password at all. Kept here, the private half " +
                    "rides the vault into your backup — so it survives a new phone, which a passkey " +
                    "kept in the phone itself does not.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The vault has to be unlocked to make or use one; a locked one offers a way " +
                    "in rather than an answer. Android keeps the choice of provider in its own " +
                    "settings, so this opens that screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                // A phone whose vendor removed the screen fails to resolve rather than crashing the
                // vault — this is the one control here that leaves for software nobody chose.
                val intent = Intent(Settings.ACTION_CREDENTIAL_PROVIDER)
                    .setData(Uri.parse("package:" + context.packageName))
                runCatching { context.startActivity(intent) }
            }) { Text("Choose Secrets in system settings") }
        }
    }
}
