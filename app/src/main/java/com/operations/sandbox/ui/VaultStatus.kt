package com.operations.sandbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.operations.vaultkit.SecretsAccess
import com.operations.vaultkit.VaultState

/**
 * What the shell knows about the vault: whether it is open, and how much is waiting on it.
 *
 * Two numbers, which is all [SecretsAccess.VaultWatcher] hands out and all the home screen needs.
 * Nothing here can read a secret, list the vault, or find out *which* credentials are queued — the
 * seam has no call for any of that, deliberately, and a status line is the last place to start
 * widening it.
 */
data class VaultStatus(
    val state: VaultState = VaultState.ABSENT,
    val pending: Int = 0
) {

    /**
     * Is there something worth interrupting somebody about?
     *
     * Only when writes are actually waiting. A locked vault on its own is not news — the vault comes
     * up shut on every process start, by design, so "Secrets is locked" would be true nearly always
     * and would therefore say nothing. What is news is that a credential has been written somewhere
     * in the suite and cannot reach the one store that survives a new phone until somebody types a
     * passphrase.
     */
    val needsAttention: Boolean get() = pending > 0

    /** "3 credentials are waiting for your vault" — the whole of the message. */
    fun message(): String {
        val plural = if (pending == 1) "credential is" else "credentials are"
        return when (state) {
            // No vault at all: the queue will land the moment one is made, which is the useful half
            // to say, because "unlock" is not a thing this household can do yet.
            VaultState.ABSENT -> "$pending $plural waiting for a vault — tap to make one"
            else -> "$pending $plural waiting for your vault — tap to unlock"
        }
    }
}

/**
 * Subscribe to the vault for as long as this composition lives.
 *
 * [SecretsAccess.watch] reports the current state on registration, so the first frame is right
 * rather than a default that corrects itself — which matters on a home screen the household sees
 * for two seconds on the way to something else.
 */
@Composable
fun rememberVaultStatus(): VaultStatus {
    var status by remember { mutableStateOf(VaultStatus()) }
    DisposableEffect(Unit) {
        val watcher = SecretsAccess.VaultWatcher { state, pending ->
            status = VaultStatus(state = state, pending = pending)
        }
        SecretsAccess.watch(watcher)
        onDispose { SecretsAccess.unwatch(watcher) }
    }
    return status
}
