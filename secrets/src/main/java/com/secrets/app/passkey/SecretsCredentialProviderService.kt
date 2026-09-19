package com.secrets.app.passkey

import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.operations.vaultkit.VaultState
import com.secrets.app.R
import com.secrets.app.SecretsApp

/**
 * Secrets, answering the system when something asks for a passkey.
 *
 * ## The Android 14 floor, stated once
 *
 * A third-party app can hold passkeys only through Credential Manager's provider API, and that API
 * is Android 14. There is no earlier route — not a hidden one, not a worse one — so this is the one
 * feature in the suite with a floor above the app's own `minSdk` of 26. Everything else keeps
 * working below it, and the settings screen says so rather than showing a switch that does nothing.
 *
 * ## Why a vault is a good place for a passkey, and a strange one
 *
 * A passkey is a key pair, and where the private half lives decides what happens the day the phone
 * does not come back. A platform passkey lives in hardware-backed storage — very good protection,
 * and the same hardware binding this whole module exists to work around. Here it lives in the sealed
 * document under a passphrase that is in somebody's head, in a file that rides the sandbox archive.
 *
 * That is the ordinary bargain of this app applied to an unusually unforgiving credential: a passkey
 * has no "forgot password" link behind it, so losing one means an account recovery flow, per site,
 * with a support queue at the end of it. The flags in the authenticator data say so honestly — this
 * authenticator reports the credential as backup-eligible and backed up, because it is, and a site
 * reads those bits to decide whether to keep a password fallback.
 *
 * ## The shape of an answer
 *
 * Same three cases as autofill, for the same reasons and with the same rule underneath:
 *
 *  - **Locked, or no vault.** No private key can be read, so the response is an *authentication
 *    action* — a way in rather than an answer. Tapping it unlocks and comes back with the entries.
 *  - **Unlocked, with credentials for this relying party.** One entry per passkey, labelled with the
 *    account it belongs to.
 *  - **Unlocked, with none.** No entries. A passkey is scoped to a relying party by construction, so
 *    "no credential for this site" is a fact rather than a failure to search hard enough — and
 *    unlike a password there is nothing sensible for a person to pick instead.
 *
 * The matching here needs no judgement and gets none: a passkey names its relying party, the request
 * names its relying party, and they either agree or they do not. That is why nothing in this file
 * resembles `AutofillMatch` — the ambiguity that made that file dangerous does not exist here.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SecretsCredentialProviderService : CredentialProviderService() {

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            // Password and custom credential types go through autofill here; declaring only the
            // public-key capability means this should not arrive, and answering an unknown type
            // with an entry would be offering to do something this app has not implemented.
            callback.onError(CreateCredentialUnknownException("only passkeys are offered here"))
            return
        }

        val account = SecretsApp.get(this).vault.let {
            if (it.state.value == VaultState.UNLOCKED) getString(R.string.secrets_passkey_save_here)
            else getString(R.string.secrets_passkey_save_here_locked)
        }

        val response = BeginCreateCredentialResponse.Builder()
            .addCreateEntry(
                CreateEntry(
                    accountName = account,
                    pendingIntent = PasskeyEntries.pendingIntent(this, PasskeyActivity.ACTION_CREATE, null)
                )
            )
            .build()
        callback.onResult(response)
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        val app = SecretsApp.get(this)
        val locked = app.vault.state.value != VaultState.UNLOCKED

        if (locked) {
            // The vault cannot say whether it holds a credential for this site without being opened,
            // which is what being locked means. So it offers the door and nothing about what is
            // behind it — an authentication action that leaks no count and no site.
            callback.onResult(
                BeginGetCredentialResponse(
                    authenticationActions = listOf(
                        AuthenticationAction(
                            title = getString(R.string.secrets_passkey_locked),
                            pendingIntent = PasskeyEntries.pendingIntent(this, PasskeyActivity.ACTION_GET, null)
                        )
                    )
                )
            )
            return
        }

        val items = app.vault.document.value?.live.orEmpty()
        val entries = PasskeyEntries.forRequest(this, request, items)

        callback.onResult(BeginGetCredentialResponse(credentialEntries = entries))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        // Nothing to clear. This request asks a provider to forget any *session* state it kept about
        // a sign-in; this one keeps none — the vault is the only state there is, and a credential
        // being forgotten is a person deleting it from the list.
        callback.onResult(null)
    }
}
