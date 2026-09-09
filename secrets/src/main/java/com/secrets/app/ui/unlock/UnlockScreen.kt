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
        val pendingMirrors: Int = 0
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
            pendingMirrors = SecretsAccess.pendingCount
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

    val deviceGate = DeviceUnlockGate { vm.unlockWithDevice(onOpened) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
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
