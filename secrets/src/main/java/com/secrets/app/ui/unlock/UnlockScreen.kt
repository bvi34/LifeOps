package com.secrets.app.ui.unlock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.operations.suite.ui.fields.SuiteTextField
import com.operations.vaultkit.PasswordGenerator
import com.operations.vaultkit.SecretOwner
import com.operations.vaultkit.SecretSources
import com.operations.vaultkit.SecretsAccess
import com.secrets.app.data.VaultStore
import com.secrets.app.ui.common.StrengthBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The door.
 *
 * Two screens in one, because they are the same door on different days: **make a vault** when there
 * is none, and **open it** when there is. What they have in common is the sentence neither of them
 * lets you past without reading — nothing here can recover a forgotten passphrase, and that is a
 * property of the design rather than a limitation somebody will lift in a later version.
 *
 * ## The passphrase is a String, and that is a compromise
 *
 * [VaultStore] takes a `CharArray` so the caller can wipe it. Compose's text field hands out a
 * `String`, which is immutable and stays in the heap until a garbage collector moves it, so between
 * the keyboard and the KDF there is a copy nobody can erase. Every Android password manager has this
 * problem and the honest options are: write a custom text field with a mutable backing buffer, or
 * accept it. This accepts it, wipes what it *can* wipe (the array handed to the store) and clears
 * the field the moment it is used. It is worth knowing rather than worth pretending about.
 */
class UnlockViewModel(private val store: VaultStore) : ViewModel() {

    data class State(
        val creating: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val corrupt: Boolean = false,
        val canUseDevice: Boolean = false,
        val pendingMirrors: Int = 0,
        /** Which apps could refill a rebuilt vault, so the reset screen can name them. */
        val refillable: List<SecretOwner> = emptyList(),
        /** What the last reset actually filed. Shown once, on the way into the new vault. */
        val refill: SecretSources.Refill? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        store.refreshState()
        _state.value = _state.value.copy(
            creating = !store.exists,
            corrupt = store.unreadable,
            canUseDevice = store.device.isEnabled,
            pendingMirrors = SecretsAccess.pendingCount,
            refillable = SecretSources.owners
        )
    }

    fun create(passphrase: String, confirmation: String, onOpened: () -> Unit) {
        if (passphrase != confirmation) {
            _state.value = _state.value.copy(error = "Those two do not match.")
            return
        }
        if (passphrase.length < MIN_PASSPHRASE) {
            _state.value = _state.value.copy(error = "Use at least $MIN_PASSPHRASE characters.")
            return
        }
        launchOp(onOpened) { store.create(passphrase.toCharArray()) }
    }

    fun unlock(passphrase: String, onOpened: () -> Unit) {
        if (passphrase.isEmpty()) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val chars = passphrase.toCharArray()
            val result = store.unlock(chars)
            chars.fill(' ')
            when (result) {
                VaultStore.UnlockResult.UNLOCKED -> {
                    _state.value = _state.value.copy(busy = false, error = null)
                    onOpened()
                }
                VaultStore.UnlockResult.WRONG_PASSPHRASE ->
                    _state.value = _state.value.copy(busy = false, error = "That is not the passphrase.")
                VaultStore.UnlockResult.NO_VAULT ->
                    _state.value = _state.value.copy(busy = false, creating = true, error = null)
                VaultStore.UnlockResult.CORRUPT ->
                    _state.value = _state.value.copy(
                        busy = false,
                        corrupt = true,
                        error = "This vault file cannot be read."
                    )
            }
        }
    }

    /** Called only after the device credential has been satisfied — see [DeviceUnlockGate]. */
    fun unlockWithDevice(onOpened: () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            when (store.unlockWithDeviceKey()) {
                VaultStore.UnlockResult.UNLOCKED -> {
                    _state.value = _state.value.copy(busy = false)
                    onOpened()
                }
                else -> _state.value = _state.value.copy(
                    busy = false,
                    canUseDevice = store.device.isEnabled,
                    error = "The device shortcut no longer opens this vault. Use your passphrase."
                )
            }
        }
    }

    /** The recovery for a vault that will not parse: fall back one save. */
    fun rollBack() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val restored = store.rollBackToPrevious()
            _state.value = _state.value.copy(
                busy = false,
                corrupt = !restored,
                error = if (restored) null else "There is no earlier copy to fall back to."
            )
            refresh()
        }
    }

    /**
     * Throw the vault away and build a new one, then let every app file what it still holds.
     *
     * The screen behind this asks twice and spells out what is lost — see
     * [VaultStore.resetForgottenPassphrase], which is where the reasoning lives. What is handled here
     * is only the reporting: the count is carried into the unlocked app rather than swallowed,
     * because "3 things came back" and "nothing came back" are very different afternoons and the
     * household should not have to go looking to find out which one they are having. The vault is
     * open by the time this returns, and the screen deliberately does *not* move on by itself: a
     * report that flashes past on the way into a list is a report nobody read.
     */
    fun resetForgotten(replacement: String, confirmation: String) {
        if (replacement != confirmation) {
            _state.value = _state.value.copy(error = "Those two do not match.")
            return
        }
        if (replacement.length < MIN_PASSPHRASE) {
            _state.value = _state.value.copy(error = "Use at least $MIN_PASSPHRASE characters.")
            return
        }
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val chars = replacement.toCharArray()
            val refill = store.resetForgottenPassphrase(chars)
            chars.fill(' ')
            if (refill == null) {
                _state.value = _state.value.copy(busy = false, error = "The vault could not be replaced.")
                return@launch
            }
            _state.value = _state.value.copy(busy = false, creating = false, refill = refill, error = null)
        }
    }

    /** A suggestion for a master passphrase, which is the one place words beat symbols. */
    fun suggestion(): String = PasswordGenerator.passphrase(words = 5, separator = "-").value

    private fun launchOp(onDone: () -> Unit, block: suspend () -> Boolean) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val ok = block()
            _state.value = _state.value.copy(
                busy = false,
                error = if (ok) null else "That did not work. Try again."
            )
            if (ok) onDone()
        }
    }

    class Factory(private val store: VaultStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UnlockViewModel(store) as T
    }

    companion object {
        /**
         * Twelve characters, which is not a security theory — it is a floor under the obviously
         * hopeless. The strength meter beside the field does the actual arguing.
         */
        const val MIN_PASSPHRASE = 12
    }
}

@Composable
fun UnlockScreen(vm: UnlockViewModel, onOpened: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }

    val deviceGate = DeviceUnlockGate { vm.unlockWithDevice(onOpened) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        val refill = state.refill
        if (refill != null) {
            ResetReport(refill = refill, onContinue = onOpened)
            return@Column
        }

        Text(
            text = if (state.creating) "Make a vault" else "Unlock",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.creating) {
                "One passphrase protects everything in here — and every credential the rest of the " +
                    "suite keeps. It is the only thing that opens this vault, on this phone or on the " +
                    "one you restore onto. Nothing in this app can recover it if it is forgotten."
            } else {
                "Type the passphrase you chose."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.corrupt) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("This vault file cannot be read", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The file is there but it is not a vault this app can parse — a truncated " +
                            "copy, or a restore that went wrong. The generation before the last save " +
                            "may still be intact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::rollBack, enabled = !state.busy) {
                        Text("Fall back one save")
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        SuiteTextField(
            label = "Passphrase",
            value = passphrase,
            onValueChange = { passphrase = it },
            capitalise = KeyboardCapitalization.None,
            imeAction = if (state.creating) ImeAction.Next else ImeAction.Done,
            trailing = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide" else "Show"
                    )
                }
            }
        )

        if (state.creating) {
            Spacer(Modifier.height(8.dp))
            StrengthBar(secret = passphrase)
            Spacer(Modifier.height(12.dp))
            SuiteTextField(
                label = "Passphrase again",
                value = confirmation,
                onValueChange = { confirmation = it },
                capitalise = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                isError = confirmation.isNotEmpty() && confirmation != passphrase
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val suggested = vm.suggestion()
                passphrase = suggested
                confirmation = suggested
                visible = true
            }) {
                Text("Suggest five words")
            }
        }

        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (state.creating) vm.create(passphrase, confirmation, onOpened)
                    else vm.unlock(passphrase, onOpened)
                    passphrase = ""
                    confirmation = ""
                },
                enabled = !state.busy && passphrase.isNotEmpty()
            ) {
                Text(if (state.creating) "Make the vault" else "Unlock")
            }
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp))
            }
        }

        if (!state.creating && state.canUseDevice) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { deviceGate(context) }, enabled = !state.busy) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Use the device lock")
            }
        }

        if (!state.creating) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { resetting = true }, enabled = !state.busy) {
                Text("I have forgotten it")
            }
        }

        if (state.pendingMirrors > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "${state.pendingMirrors} credential${if (state.pendingMirrors == 1) "" else "s"} " +
                    "from other apps are waiting to be filed here. Unlocking saves them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (resetting) {
        ResetDialog(
            refillable = state.refillable,
            busy = state.busy,
            onDismiss = { resetting = false },
            onConfirm = { replacement, again ->
                resetting = false
                vm.resetForgotten(replacement, again)
            }
        )
    }
}

/**
 * What a reset did, shown on the way in rather than as a toast on the way past.
 *
 * A destructive operation that reports "done" has told the household nothing they can act on. This
 * names the apps and the counts, and — when nothing came back — says that plainly instead of
 * dressing an empty result as a success. The vault is already open behind this card; the only thing
 * the button does is stop reading.
 */
@Composable
private fun ResetReport(refill: SecretSources.Refill, onContinue: () -> Unit) {
    Text("The vault has been replaced", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (refill.empty) {
                Text("Nothing was filed back", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "No other app had a credential stored on this phone to give back. The new vault " +
                        "is empty, and anything the apps connect from now on will be filed in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val plural = if (refill.filed == 1) "credential" else "credentials"
                Text(
                    "${refill.filed} $plural filed again",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(refill.summary(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Those apps kept their own working copies all along — the vault was the spare, " +
                        "and it is a spare again. What you typed in yourself is not here: only a " +
                        "backup taken before the reset still holds that, and only the old passphrase " +
                        "opens it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onContinue) { Text("Open the vault") }
}

/**
 * What a reset says before it does anything.
 *
 * This is the most destructive button in the suite and the copy is the safety feature. Three things
 * have to be understood before somebody presses it, and none of them are obvious:
 *
 *  1. **The passwords you typed in are gone.** Not "reset", not "recoverable by support" — gone,
 *    because the vault was the only place they were. That is stated first and without softening.
 *  2. **The apps' credentials come back**, and it is worth saying *which* apps, by name, because
 *    "some things will be restored" is exactly the kind of reassurance that turns out to be worth
 *    nothing. If no app is registered, the dialog says nothing will come back rather than implying
 *    something might.
 *  3. **An old backup still opens with the old passphrase.** If it surfaces later — written down
 *    somewhere, remembered in the shower — the archive is still a route back to what was typed in,
 *    through the merge on the settings screen. A reset does not burn that.
 */
@Composable
private fun ResetDialog(
    refillable: List<SecretOwner>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the vault again") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Nothing can open the old vault without its passphrase — not this app, not " +
                        "anybody. So this does not recover it. It deletes it and builds a new one.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("What you lose", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Everything you typed into Secrets yourself — logins, notes, cards. The vault " +
                        "was the only place those existed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("What comes back", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (refillable.isEmpty()) {
                        "Nothing, on this phone. No other app has anything filed here to give back."
                    } else {
                        "The credentials the other apps still hold on this phone — " +
                            refillable.joinToString(", ") { it.displayName } +
                            " — are filed again automatically. Their own stores kept working copies " +
                            "all along; the vault was the spare."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("And if the passphrase turns up later", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Backups taken before now still open with it. Restore one and Settings will " +
                        "offer to merge it, which brings the typed-in items back too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                SuiteTextField(
                    label = "New passphrase",
                    value = replacement,
                    onValueChange = { replacement = it },
                    capitalise = KeyboardCapitalization.None
                )
                if (replacement.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    StrengthBar(secret = replacement)
                }
                Spacer(Modifier.height(8.dp))
                SuiteTextField(
                    label = "New passphrase again",
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    capitalise = KeyboardCapitalization.None,
                    isError = confirmation.isNotEmpty() && confirmation != replacement
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(replacement, confirmation) },
                enabled = !busy &&
                    replacement.length >= UnlockViewModel.MIN_PASSPHRASE &&
                    replacement == confirmation
            ) { Text("Delete and start again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The device credential prompt, as a function you call with a context.
 *
 * `createConfirmDeviceCredentialIntent` rather than `BiometricPrompt`: it needs no extra dependency,
 * it covers fingerprint, face, PIN and pattern in one call because it is the *lock screen*, and it
 * fails closed on a phone with no lock screen (the intent is null, and [com.secrets.app.data.DeviceUnlock]
 * refuses to store anything on such a phone in the first place).
 */
@Composable
private fun DeviceUnlockGate(onSatisfied: () -> Unit): (Context) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onSatisfied()
    }
    return { context ->
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            "Unlock your vault",
            "Use your screen lock to open Secrets"
        )
        if (intent != null) launcher.launch(intent)
    }
}
