package com.secrets.app.ui.importer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.operations.vaultkit.VaultState
import com.secrets.app.SecretsApp
import com.secrets.app.ui.theme.SecretsTheme
import com.secrets.app.ui.unlock.UnlockScreen
import com.secrets.app.ui.unlock.UnlockViewModel

/**
 * The export, sent straight here from the app that made it.
 *
 * ## What this is for
 *
 * Chrome's "Export passwords" ends in the system share sheet. Without somewhere to send it, the
 * household's next three steps are *save*, *lose*, and *find again in a picker* — and the file they
 * are carrying through those three steps is every password they have in plain text. This activity
 * is the share sheet entry that collapses them into one: export, pick Secrets, review, done. The
 * file never has to be saved anywhere at all.
 *
 * It is the same import as the one in Settings; only the way the file arrives is different.
 *
 * ## It is the third exported component in this module, and the first one an app can reach
 *
 * The other two — the autofill service and the credential provider — are exported behind
 * permissions the platform holds and nothing installable does, so "exported" there means *the
 * operating system may bind this*. This one is different in kind and the manifest says so: any app
 * on the phone can send it a file. What keeps that narrow is the shape of the thing:
 *
 *  - **It takes and never gives.** There is no result, no returned data, and nothing it can be
 *    asked for. An app that sends a file learns nothing — not whether a vault exists, not whether
 *    it was unlocked, not what was in it.
 *  - **The vault still has to be opened,** by the passphrase or the device shortcut, on this
 *    screen, by the person holding the phone.
 *  - **Nothing is written without a review.** What arrives is a *proposal*: a list somebody ticks.
 *    The worst a hostile share can achieve is a screen offering to add items that were never
 *    asked for — visible, itemised, and declined by pressing Cancel.
 *  - **The payload is treated as hostile input,** which it is: bounded before it is read, parsed by
 *    `ImportFile` into data and never into behaviour, and refused with a sentence when it is not an
 *    export at all.
 *  - `FLAG_SECURE`, like every other screen here.
 *
 * ## And it closes when the vault does
 *
 * A plan is plaintext in memory — every password in the file, and what each would do. The main
 * activity handles that by collapsing to the unlock screen when the vault shuts; this one has
 * nowhere to collapse to, so it finishes, taking the plan with it.
 */
class ImportShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val shared = sharedFile(intent)
        val app = SecretsApp.get(this)
        val importer = ViewModelProvider(this, ImportViewModel.Factory(app.vault))
            .get(ImportViewModel::class.java)

        setContent {
            SecretsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vaultState by app.vault.state.collectAsStateWithLifecycle()
                    var opened by remember { mutableStateOf(false) }

                    // Shut, after it was open: the plan goes with the activity rather than waiting
                    // in memory for whoever picks the phone up next.
                    LaunchedEffect(vaultState, opened) {
                        if (opened && vaultState != VaultState.UNLOCKED) finish()
                    }

                    when {
                        shared == null -> NothingArrived(onClose = ::finish)

                        vaultState != VaultState.UNLOCKED -> {
                            val unlock: UnlockViewModel =
                                viewModel(factory = UnlockViewModel.Factory(app.vault))
                            UnlockScreen(vm = unlock, onOpened = { })
                        }

                        else -> {
                            LaunchedEffect(Unit) {
                                opened = true
                                importer.open(contentResolver, shared)
                            }
                            ImportScreen(vm = importer, onDone = ::finish)
                        }
                    }
                }
            }
        }
    }

    companion object {

        /**
         * The file behind a share, or null if there is not one.
         *
         * Only `ACTION_SEND` with a stream: a share that carries text rather than a file is
         * somebody sending a *password* to this app, and answering that with an import screen
         * would be answering it at all. The action is checked rather than assumed because an
         * exported component is started by whoever wants to, with whatever they like in the intent.
         */
        fun sharedFile(intent: Intent?): Uri? {
            if (intent == null || intent.action != Intent.ACTION_SEND) return null
            return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }
    }
}

@Composable
private fun NothingArrived(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Nothing arrived", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "That share carried no file. An export is a .csv or a .1pux — if the " +
                        "other app offered to send the passwords as text instead, decline: a " +
                        "password list pasted between apps is a password list in every clipboard " +
                        "and notification log on the phone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Button(onClick = onClose) { Text("Close") }
    }
}
