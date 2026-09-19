package com.secrets.app.passkey

import android.content.Intent
import android.content.pm.SigningInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.operations.vaultkit.PasskeyRequest
import com.operations.vaultkit.Passkeys
import com.operations.vaultkit.VaultItem
import com.operations.vaultkit.VaultItemKind
import com.operations.vaultkit.VaultState
import com.operations.vaultkit.WebAuthn
import com.secrets.app.R
import com.secrets.app.SecretsApp
import com.secrets.app.ui.theme.SecretsTheme
import com.secrets.app.ui.unlock.UnlockScreen
import com.secrets.app.ui.unlock.UnlockViewModel
import java.util.UUID

/**
 * Where a passkey is actually made or used.
 *
 * The service answers the system with *entries*; nothing it returns can contain a private key,
 * because the service runs whether or not anybody has unlocked anything. The moment a person taps
 * one of those entries the system starts this, and this is the only place the credential is touched.
 *
 * Three ways in, and they are three different Credential Manager conversations rather than three
 * flavours of one:
 *
 *  - **Create** — a site wants a new passkey. Parse its options, generate a key pair, file it in the
 *    vault, hand back the registration.
 *  - **Get, with an entry** — somebody picked a specific credential. Sign the challenge with it.
 *  - **Get, with no entry** — somebody tapped "Secrets is locked". There was no list to pick from,
 *    so the answer to this one is *the list*: unlock, then return the entries the service could not
 *    produce, and the system shows them.
 *
 * Not exported. The system starts it through a `PendingIntent` this app created, which carries this
 * app's identity rather than the caller's. `FLAG_SECURE` like every other screen here.
 */
class PasskeyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val app = SecretsApp.get(this)

        // A cancelled or failed flow must still leave a result, or the system is left holding a
        // dialog that never resolves.
        setResult(RESULT_CANCELED)

        setContent {
            SecretsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vaultState by app.vault.state.collectAsStateWithLifecycle()

                    if (vaultState != VaultState.UNLOCKED) {
                        // Everything below needs a private key, so there is nothing to do here but
                        // open the vault. The device shortcut makes this one fingerprint.
                        val vm: UnlockViewModel = viewModel(factory = UnlockViewModel.Factory(app.vault))
                        UnlockScreen(vm = vm, onOpened = { })
                    } else {
                        Working()
                        LaunchedEffect(Unit) { proceed() }
                    }
                }
            }
        }
    }

    private fun proceed() {
        when (intent.action) {
            ACTION_CREATE -> create()
            ACTION_GET -> get()
            else -> finish()
        }
    }

    // --- Making one --------------------------------------------------------------------------------

    private fun create() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val calling = request?.callingRequest as? CreatePublicKeyCredentialRequest
        if (request == null || calling == null) {
            failCreate(getString(R.string.secrets_passkey_unreadable))
            return
        }

        val options = PasskeyRequest.parseCreation(calling.requestJson)
        if (options == null) {
            failCreate(getString(R.string.secrets_passkey_unreadable))
            return
        }

        val app = SecretsApp.get(this)
        val existing = app.vault.document.value?.live.orEmpty()

        // The site says which credentials it already has and does not want duplicated. Making a
        // second one anyway produces an account with two passkeys where the person believes they
        // have one, and a site that may honour only the first.
        val alreadyHas = options.excludeCredentialIds.any { excluded ->
            existing.any { item ->
                item.passkey?.let { PasskeyRequest.sameCredential(excluded, it.credentialId) } == true
            }
        }
        if (alreadyHas) {
            failCreate(getString(R.string.secrets_passkey_already_here))
            return
        }

        val client = clientData("webauthn.create", options.challenge, calling.clientDataHash, request.callingAppInfo)
        val registration = Passkeys.register(
            options = options,
            clientDataJson = client.json,
            clientDataHash = client.hash,
            now = System.currentTimeMillis()
        )
        if (registration == null) {
            failCreate(getString(R.string.secrets_passkey_algorithm))
            return
        }

        val now = System.currentTimeMillis()
        val saved = app.vault.mutateBlocking { document ->
            document.upsert(
                VaultItem(
                    id = UUID.randomUUID().toString(),
                    kind = VaultItemKind.PASSKEY,
                    // Titled and addressed after the site so that the ordinary search finds it —
                    // the passkey's own fields are never searched, because one of them is the key.
                    title = options.rpName.ifBlank { options.rpId },
                    username = options.userName.ifBlank { options.userDisplayName },
                    url = options.rpId,
                    passkey = registration.passkey,
                    createdAt = now,
                    updatedAt = now
                ),
                now
            )
        }
        if (!saved) {
            // Handing back a credential this vault failed to store would leave the site believing
            // in a key that exists nowhere — an account nobody can ever sign in to again.
            failCreate(getString(R.string.secrets_passkey_not_saved))
            return
        }

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            result,
            CreatePublicKeyCredentialResponse(registration.responseJson)
        )
        setResult(RESULT_OK, result)
        finish()
    }

    // --- Using one ---------------------------------------------------------------------------------

    private fun get() {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        if (itemId == null) {
            // Came from "Secrets is locked": the vault is open now, so the answer is the list the
            // service could not produce while it was shut.
            answerWithEntries()
            return
        }

        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val option = request?.credentialOptions?.firstOrNull { it is GetPublicKeyCredentialOption }
            as? GetPublicKeyCredentialOption
        if (request == null || option == null) {
            failGet(getString(R.string.secrets_passkey_unreadable))
            return
        }

        val asked = PasskeyRequest.parseAssertion(option.requestJson)
        val passkey = SecretsApp.get(this).vault.document.value?.item(itemId)?.passkey
        if (asked == null || passkey == null) {
            failGet(getString(R.string.secrets_passkey_gone))
            return
        }

        // The entry was built for this relying party, but the request is re-read here rather than
        // trusted: an entry and a request that disagree would mean signing a challenge for a site
        // this credential does not belong to.
        if (!passkey.rpId.equals(asked.rpId, ignoreCase = true)) {
            failGet(getString(R.string.secrets_passkey_wrong_site))
            return
        }

        val client = clientData("webauthn.get", asked.challenge, option.clientDataHash, request.callingAppInfo)
        val assertion = Passkeys.assertion(passkey, client.json, client.hash)
        if (assertion == null) {
            failGet(getString(R.string.secrets_passkey_unusable))
            return
        }

        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            GetCredentialResponse(PublicKeyCredential(assertion))
        )
        setResult(RESULT_OK, result)
        finish()
    }

    private fun answerWithEntries() {
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
            finish()
            return
        }
        val items = SecretsApp.get(this).vault.document.value?.live.orEmpty()
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(
            result,
            BeginGetCredentialResponse(
                credentialEntries = PasskeyEntries.forRequest(this, request, items)
            )
        )
        setResult(RESULT_OK, result)
        finish()
    }

    // --- Client data -------------------------------------------------------------------------------

    private class ClientData(val json: String?, val hash: ByteArray)

    /**
     * What gets signed alongside the authenticator data.
     *
     * Two cases, and the difference is who the caller is. A **privileged** caller — a browser the
     * platform vouches for — has already built the client data for the page it is showing, and hands
     * over only its hash; this app signs that and never sees the JSON, which is correct, because the
     * origin in it is the *page's* and only the browser can honestly state it.
     *
     * Everything else is an ordinary app, which has no URL, so WebAuthn names it by the hash of the
     * certificate its APK was signed with. A relying party checks that against its own
     * `assetlinks.json`, and that check is what stops an app which merely claims to be the bank from
     * being handed the bank's passkey. This app does not get to skip building it honestly: the
     * certificate comes from the platform's own [SigningInfo] for the caller, not from anything the
     * caller said about itself.
     */
    private fun clientData(
        type: String,
        challenge: String,
        providedHash: ByteArray?,
        calling: CallingAppInfo?
    ): ClientData {
        if (providedHash != null) return ClientData(json = null, hash = providedHash)

        val origin = calling?.let { WebAuthn.apkOrigin(signingCertificateSha256(it.signingInfo)) }
            ?: ""
        val json = WebAuthn.clientDataJson(type, challenge, origin)
        return ClientData(json = json, hash = WebAuthn.sha256(json.toByteArray(Charsets.UTF_8)))
    }

    /**
     * The SHA-256 of the caller's signing certificate.
     *
     * `apkContentsSigners` when an APK has several signers, and the rotation history otherwise —
     * whose first entry is the original certificate, which is the one a relying party's
     * `assetlinks.json` was written against and kept pointing at across a key rotation.
     */
    private fun signingCertificateSha256(info: SigningInfo): ByteArray {
        val signatures = if (info.hasMultipleSigners()) {
            info.apkContentsSigners
        } else {
            info.signingCertificateHistory
        }
        val certificate = signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
        return WebAuthn.sha256(certificate)
    }

    // --- Failing out --------------------------------------------------------------------------------

    private fun failCreate(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException(message))
        setResult(RESULT_OK, result)
        finish()
    }

    private fun failGet(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException(message))
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val ACTION_CREATE = "com.secrets.app.passkey.CREATE"
        const val ACTION_GET = "com.secrets.app.passkey.GET"
        const val EXTRA_ITEM_ID = "item_id"
    }
}

/**
 * The half-second between the vault opening and the answer going back.
 *
 * Worth a screen rather than a blank one: key generation and a signature are fast, but a window that
 * appears and vanishes with nothing in it reads as a crash.
 */
@androidx.compose.runtime.Composable
private fun Working() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Secrets", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Signing with your passkey…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
